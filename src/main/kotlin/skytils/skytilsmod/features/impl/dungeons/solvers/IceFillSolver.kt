/*
 * Skytils - Hypixel Skyblock Quality of Life Mod
 * Copyright (C) 2022 Skytils
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package skytils.skytilsmod.features.impl.dungeons.solvers

import gg.essential.universal.UMatrixStack
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.init.Blocks
import net.minecraft.tileentity.TileEntityChest
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing
import net.minecraft.util.Vec3
import net.minecraft.world.World
import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import skytils.skytilsmod.Skytils
import skytils.skytilsmod.Skytils.Companion.mc
import skytils.skytilsmod.listeners.DungeonListener
import skytils.skytilsmod.utils.RenderUtil
import skytils.skytilsmod.utils.Utils
import java.awt.Color
import java.util.concurrent.Future

object IceFillSolver {
    private var ticks = 0
    private var chestPos: BlockPos? = null
    private var roomFacing: EnumFacing? = null
    private var three: IceFillPuzzle? = null
    private var five: IceFillPuzzle? = null
    private var seven: IceFillPuzzle? = null
    private var job: Future<*>? = null

    @SubscribeEvent
    fun onTick(event: ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START || !Utils.inDungeons || mc.thePlayer == null || mc.theWorld == null) return
        if (!Skytils.config.iceFillSolver) return
        val world: World = mc.theWorld
        if (ticks % 20 == 0) {
            if (DungeonListener.missingPuzzles.contains("Ice Fill") && (job == null || job?.isCancelled == true || job?.isDone == true)) {
                if (chestPos == null || roomFacing == null) {
                    job = Skytils.threadPool.submit {
                        findChest@ for (te in mc.theWorld.loadedTileEntityList) {
                            val playerX = mc.thePlayer.posX.toInt()
                            val playerZ = mc.thePlayer.posZ.toInt()
                            val xRange = playerX - 25..playerX + 25
                            val zRange = playerZ - 25..playerZ + 25
                            if (te.pos.y == 75 && te is TileEntityChest && te.numPlayersUsing == 0 && te.pos.x in xRange && te.pos.z in zRange
                            ) {
                                val pos = te.pos
                                if (world.getBlockState(pos.down()).block == Blocks.stone) {
                                    for (direction in EnumFacing.HORIZONTALS) {
                                        if (world.getBlockState(pos.offset(direction)).block == Blocks.cobblestone && world.getBlockState(
                                                pos.offset(direction.opposite, 2)
                                            ).block == Blocks.iron_bars && world.getBlockState(
                                                pos.offset(
                                                    direction.rotateY(),
                                                    2
                                                )
                                            ).block == Blocks.torch && world.getBlockState(
                                                pos.offset(
                                                    direction.rotateYCCW(),
                                                    2
                                                )
                                            ).block == Blocks.torch && world.getBlockState(
                                                pos.offset(direction.opposite).down(2)
                                            ).block == Blocks.stone_brick_stairs
                                        ) {
                                            chestPos = pos
                                            roomFacing = direction
                                            println(
                                                "Ice fill chest is at $chestPos and is facing $roomFacing"
                                            )
                                            break@findChest
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (chestPos != null) {
                    job = Skytils.threadPool.submit {
                        three =
                            (three ?: IceFillPuzzle(world, 70))
                        five = (five ?: IceFillPuzzle(world, 71))
                        seven =
                            (seven ?: IceFillPuzzle(world, 72))
                    }
                }
            }
            ticks = 0
        }
        ticks++
    }

    private fun checkForStart(world: World, pos: BlockPos) =
        world.getBlockState(pos).block === Blocks.air &&
                world.getBlockState(pos.offset(roomFacing!!.rotateY())).block === Blocks.cobblestone_wall &&
                world.getBlockState(pos.offset(roomFacing!!.rotateYCCW())).block === Blocks.cobblestone_wall

    private fun generatePairs(world: World, positions: List<BlockPos>) =
        positions.flatMap { pos -> getPossibleMoves(world, pos).map { Move(pos, it) } }

    private fun getPossibleMoves(world: World, pos: BlockPos) =
        EnumFacing.HORIZONTALS.map { pos.offset(it) }.filter { spot ->
            val down = world.getBlockState(spot.down()).block
            (down == Blocks.ice || down == Blocks.packed_ice) && world.getBlockState(spot).block != Blocks.stone
        }

    @SubscribeEvent
    fun onWorldRender(event: RenderWorldLastEvent) {
        if (!Skytils.config.iceFillSolver) return
        if (chestPos != null && roomFacing != null) {
            val matrixStack = UMatrixStack.Compat.get()
            three?.draw(matrixStack, event.partialTicks)
            five?.draw(matrixStack, event.partialTicks)
            seven?.draw(matrixStack, event.partialTicks)
        }
    }

    @SubscribeEvent
    fun onWorldChange(event: WorldEvent.Load) {
        chestPos = null
        roomFacing = null
        three = null
        five = null
        seven = null
    }

    private class IceFillPuzzle(world: World, y: Int) {
        private val spaces: MutableList<BlockPos> = ArrayList()
        private lateinit var start: BlockPos
        var paths: MutableSet<List<BlockPos>> = HashSet()
        fun genPaths(world: World) {
            // Generate paths
            val moves = generatePairs(world, spaces)
            val g = Graph(moves, world)
            val path: MutableList<BlockPos> = ArrayList()
            path.add(start)
            try {
                getPaths(g, start, mutableSetOf(start), path, spaces.size)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun draw(matrixStack: UMatrixStack, partialTicks: Float) =
            paths.firstOrNull()?.zipWithNext { first, second ->
                GlStateManager.disableCull()
                RenderUtil.draw3DLine(
                    Vec3(first).addVector(0.5, 0.01, 0.5),
                    Vec3(second).addVector(0.5, 0.01, 0.5),
                    5,
                    Color.RED,
                    partialTicks,
                    matrixStack
                )
                GlStateManager.enableCull()
            }

        private fun getPaths(
            g: Graph,
            v: BlockPos,
            visited: MutableSet<BlockPos>,
            path: MutableList<BlockPos>,
            N: Int
        ) {
            if (path.size == N) {
                val newPath: List<BlockPos> = path.toList()
                paths.add(newPath)
                return
            } else {

                // Check if every move starting from position `v` leads
                // to a solution or not
                g.adjList[v]?.forEach { w ->
                    // Only check if we haven't been there before
                    if (!visited.contains(w)) {
                        visited.add(w)
                        path.add(w)

                        // Continue checking down this path
                        getPaths(g, w, visited, path, N)

                        // backtrack
                        visited.remove(w)
                        path.remove(w)
                    }
                }
            }
        }

        init {
            chestPos?.offset(roomFacing?.opposite, 11)?.run { Utils.getBlocksWithinRangeAtSameY(chestPos!!, 25, y) }
                ?.forEach { pos ->
                    when (world.getBlockState(pos.down()).block) {
                        Blocks.ice, Blocks.packed_ice ->
                            if (world.getBlockState(pos).block === Blocks.air)
                                spaces.add(pos)
                        Blocks.stone_brick_stairs, Blocks.stone -> {
                            if (!::start.isInitialized && checkForStart(world, pos))
                                start = pos.offset(roomFacing)
                        }
                    }
                } ?: throw NullPointerException("Chest is null! (how)")
            genPaths(world)
        }
    }

    private data class Move(var source: BlockPos, var dest: BlockPos)

    private class Graph(moves: Collection<Move>, world: World) {
        val adjList: Map<BlockPos, Collection<BlockPos>> = buildMap {
            moves.forEach { (source, dest) ->
                this[source] = getPossibleMoves(world, source)
                this[dest] = getPossibleMoves(world, dest)
            }
        }
    }
}