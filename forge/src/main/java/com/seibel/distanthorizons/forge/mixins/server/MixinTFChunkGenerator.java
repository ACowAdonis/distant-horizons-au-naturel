package com.seibel.distanthorizons.forge.mixins.server;

import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkGenerator.class)
class MixinTFChunkGenerator { }