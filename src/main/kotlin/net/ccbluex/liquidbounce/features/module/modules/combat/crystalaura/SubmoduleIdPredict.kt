/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.destroy.SubmoduleCrystalDestroyer
import net.ccbluex.liquidbounce.features.module.modules.render.debug.debugParameter
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.phys.AABB
import kotlin.math.max

/**
 * Allows the crystal aura to send a break packet right when a crystal is placed by predicting the
 * expected entity id.
 */
object SubmoduleIdPredict : ToggleableValueGroup(ModuleCrystalAura, "IDPredict", false) {

    /**
     * Sends a packet for all included offsets.
     */
    private val offsetRange by intRange("OffsetRange", 1..1, 1..100)

    /**
     * Swings before every attack. Otherwise, it will only swing once.
     *
     * Only works when [SubmoduleCrystalDestroyer.swingMode] is enabled.
     */
    private val swingAlways by boolean("SwingAlways", false)

    /**
     * Pauses the prediction after an item, experience orb or arrow spawned. The server disconnects with
     * "invalid entity attacked" when an attack resolves to one of those, and while they are spawning the
     * next id is likely to belong to another one of them instead of the crystal.
     */
    private val hazardPause by int("HazardPause", 150, 0..1000, "ms")

    private val hazardChronometer = Chronometer()

    private val hazardousTypes = setOf<EntityType<*>>(
        EntityTypes.ITEM,
        EntityTypes.EXPERIENCE_ORB,
        EntityTypes.ARROW,
        EntityTypes.SPECTRAL_ARROW,
        EntityTypes.TRIDENT
    )

    /**
     * Sends an additional rotation packet.
     */
    private object Rotate : ToggleableValueGroup(this, "Rotate", true) {

        val back by boolean("Back", false)

        var oldRotation: Rotation? = null

        fun sendRotation(rotation: Rotation) {
            if (!enabled) {
                return
            }

            oldRotation = RotationManager.serverRotation
            network.send(
                ServerboundMovePlayerPacket.PosRot(
                player.x,
                player.y,
                player.z,
                rotation.yaw,
                rotation.pitch,
                player.onGround(),
                player.horizontalCollision
            ))
        }

        fun rotateBack() {
            if (!enabled || !back || oldRotation == null) {
                return
            }

            network.send(
                ServerboundMovePlayerPacket.PosRot(
                player.x,
                player.y,
                player.z,
                oldRotation!!.yaw,
                oldRotation!!.pitch,
                player.onGround(),
                player.horizontalCollision
            ))
        }

    }

    init {
        tree(Rotate)
    }

    private var highestId = 0
        set(value) {
            field = value
            ModuleCrystalAura.debugParameter("Highest ID") { highestId }
        }

    override fun onEnabled() {
        reset()
    }

    fun run(placePos: BlockPos) {
        if (!enabled) {
            return
        }

        if (!hazardChronometer.hasElapsed(hazardPause.toLong())) {
            return
        }

        val (rotation, _) =
            raytraceBox(
                player.eyePosition,
                AABB(placePos).inflate(0.5, 0.0, 0.5).setMaxY(placePos.y + 2.0),
                range = SubmoduleCrystalDestroyer.range.toDouble(),
                wallsRange = SubmoduleCrystalDestroyer.wallsRange.toDouble(),
            ) ?: return

        val normalized = rotation.normalize()
        // A non-finite rotation serialises into a packet the server rejects outright.
        if (!normalized.yaw.isFinite() || !normalized.pitch.isFinite()) {
            return
        }

        Rotate.sendRotation(normalized)

        val swingMode = SubmoduleCrystalDestroyer.swingMode
        if (!swingAlways) {
            swingMode.attackSwing()
        }

        // Without a baseline the prediction is a blind guess at the start of the id space.
        if (highestId <= 0) {
            reset()

            if (highestId <= 0) {
                return
            }
        }

        offsetRange.forEach { idOffset ->
            val id = highestId + idOffset

            // Never attack an id that resolves to something that is not a live crystal. Unknown ids are
            // still sent, because that is the prediction this feature exists for, but a known removed or
            // wrong entity never is.
            val entity = world.getEntity(id)
            if (entity != null && (entity !is EndCrystal || entity.isRemoved || !entity.isAlive)) {
                return@forEach
            }

            if (swingAlways) {
                swingMode.attackSwing()
            }

            network.send(ServerboundAttackPacket(id))
            SubmoduleCrystalDestroyer.postAttackHandlers.forEach { it.attacked(id) }
        }

        SubmoduleCrystalDestroyer.chronometer.reset()
        Rotate.rotateBack()
    }

    private fun reset() {
        highestId = 0
        world.entitiesForRendering().forEach {
            highestId = max(it.id, highestId)
        }
    }

    @Suppress("unused")
    private val entitySpawnHandler = handler<PacketEvent> {
        when(val packet = it.packet) {
            // TODO: I guess this packet is merged into EntitySpawnS2CPacket
//            is ExperienceOrbSpawnS2CPacket -> highestId = max(packet.entityId, highestId)
            is ClientboundAddEntityPacket -> {
                highestId = max(packet.id, highestId)

                if (packet.type in hazardousTypes) {
                    hazardChronometer.reset()
                }
            }
            is ClientboundLoginPacket -> highestId = max(packet.playerId, highestId)
        }
    }

    @Suppress("unused")
    val worldChangeHandler = handler<WorldChangeEvent> {
        reset()
    }

}
