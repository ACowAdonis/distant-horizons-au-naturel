package com.seibel.distanthorizons.forge.mixins;

import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * @author coolGi
 * @author cortex
 */
public class ForgeMixinPlugin implements IMixinConfigPlugin
{
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName)
	{
		if (mixinClassName.contains(".mods."))
		{
			// If the mixin targets a mod, check if that mod is loaded
			// Eg. "com.seibel.distanthorizons.mixins.mods.sodium.MixinSodiumChunkRenderer" turns into "sodium"
			return ModList.get().isLoaded(
					mixinClassName
							.replaceAll("^.*mods.", "") // Replaces everything before the mods
							.replaceAll("\\..*$", "") // Replaces everything after the mod name
			);
		}

		return true;
	}
	
	
	@Override
	public void onLoad(String mixinPackage) { }
	
	@Override
	public String getRefMapperConfig() { return null; }
	
	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	
	@Override
	public List<String> getMixins() { return null; }
	
	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	
	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	
}