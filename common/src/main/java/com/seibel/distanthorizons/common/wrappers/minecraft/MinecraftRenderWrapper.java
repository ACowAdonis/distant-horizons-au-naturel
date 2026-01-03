/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.common.wrappers.minecraft;

import java.awt.Color;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.ColorUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.ILightMapWrapper;

import net.minecraft.client.renderer.FogRenderer;
import com.mojang.blaze3d.systems.RenderSystem;


import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractOptifineAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IDimensionTypeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;

import net.minecraft.world.phys.Vec3;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector4f;

import net.minecraft.world.level.material.FogType;



/**
 * A singleton that contains everything
 * related to rendering in Minecraft.
 */
public class MinecraftRenderWrapper implements IMinecraftRenderWrapper
{
	public static final MinecraftRenderWrapper INSTANCE = new MinecraftRenderWrapper();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final Minecraft MC = Minecraft.getInstance();
	
	/** 
	 * In the case of immersive portals multiple levels may be active at once, causing conflicting lightmaps. <br> 
	 * Requiring the use of multiple {@link LightMapWrapper}.
	 */
	public ConcurrentHashMap<IDimensionTypeWrapper, LightMapWrapper> lightmapByDimensionType = new ConcurrentHashMap<>();
	
	/** 
	 * Holds the render buffer that should be used when displaying levels to the screen.
	 * This is used for Optifine shader support so we can render directly to Optifine's level frame buffer.
	 */
	public int finalLevelFrameBufferId = -1;
	
	public boolean colorTextureCastFailLogged = false;
	public boolean depthTextureCastFailLogged = false;
	
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public Vec3f getLookAtVector()
	{
		Camera camera = MC.gameRenderer.getMainCamera();
		return new Vec3f(camera.getLookVector().x(), camera.getLookVector().y(), camera.getLookVector().z());
	}
	
	/** 
	 * Unless you really need to know if the player is blind, 
	 * use {@link MinecraftRenderWrapper#isFogStateSpecial()} or {@link IMinecraftRenderWrapper#isFogStateSpecial()} instead 
	 */
	@Override
	public boolean playerHasBlindingEffect()
	{
		if (MC.player == null)
		{
			return false;
		}
		else if (MC.player.getActiveEffectsMap() == null)
		{
			return false;
		}
		else
		{
			return MC.player.getActiveEffectsMap().get(MobEffects.BLINDNESS) != null
					|| MC.player.getActiveEffectsMap().get(MobEffects.DARKNESS) != null // Deep dark effect
					;
		}
	}
	
	@Override
	public Vec3d getCameraExactPosition()
	{
		Camera camera = MC.gameRenderer.getMainCamera();
		Vec3 projectedView = camera.getPosition();
		
		return new Vec3d(projectedView.x, projectedView.y, projectedView.z);
	}
	
	@Override
	public Color getFogColor(float partialTicks)
	{
		FogRenderer.setupColor(MC.gameRenderer.getMainCamera(), partialTicks, MC.level, 1, MC.gameRenderer.getDarkenWorldAmount(partialTicks));
		float[] colorValues = RenderSystem.getShaderFogColor();
		return new Color(
				Math.max(0f, Math.min(colorValues[0], 1f)), // r
				Math.max(0f, Math.min(colorValues[1], 1f)), // g
				Math.max(0f, Math.min(colorValues[2], 1f)), // b
				Math.max(0f, Math.min(colorValues[3], 1f))  // a
		);
	}
	
	@Override
	public Color getSkyColor()
	{
		if (MC.level.dimensionType().hasSkyLight())
		{
			float frameTime;
			frameTime = MC.getFrameTime();
			
			Vec3 colorValues = MC.level.getSkyColor(MC.gameRenderer.getMainCamera().getPosition(), frameTime);
			return new Color((float) colorValues.x, (float) colorValues.y, (float) colorValues.z);
		}
		else
		{
			return new Color(0, 0, 0);
		}
	}
	
	@Override
	public double getFov(float partialTicks) { return MC.gameRenderer.getFov(MC.gameRenderer.getMainCamera(), partialTicks, true); }
	
	/** Measured in chunks */
	@Override
	public int getRenderDistance()
	{
		return MC.options.getEffectiveRenderDistance();
	}
	
	protected RenderTarget getRenderTarget() { return MC.getMainRenderTarget(); }
	
	@Override
	public boolean mcRendersToFrameBuffer()
	{
		return true;
	}
	
	@Override
	public boolean runningLegacyOpenGL()
	{
		return false;
	}
	
	@Override
	public int getTargetFramebuffer()
	{
		// used so we can access the framebuffer shaders end up rendering to
		if (AbstractOptifineAccessor.optifinePresent())
		{
			return this.finalLevelFrameBufferId;
		}
		
		return this.getRenderTarget().frameBufferId;
	}
	
	@Override
	public void clearTargetFrameBuffer() { this.finalLevelFrameBufferId = -1; }
	
	@Override
	public int getDepthTextureId()
	{
		return this.getRenderTarget().getDepthTextureId();
	}
	@Override
	public int getColorTextureId() 
	{
		return this.getRenderTarget().getColorTextureId();
	}
	
	@Override
	public int getTargetFramebufferViewportWidth()
	{
		return this.getRenderTarget().viewWidth;
	}
	
	@Override
	public int getTargetFramebufferViewportHeight()
	{
		return this.getRenderTarget().viewHeight;
	}
	
	@Override
	public ILightMapWrapper getLightmapWrapper(@NotNull ILevelWrapper level) { return this.lightmapByDimensionType.get(level.getDimensionType()); }
	
	@Override
	public boolean isFogStateSpecial()
	{
		boolean isBlind = this.playerHasBlindingEffect();
		return MC.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE || isBlind;
	}
	
	/** 
	 * It's better to use {@link MinecraftRenderWrapper#setLightmapId(int, IClientLevelWrapper)} if possible,
	 * however old MC versions don't support it.
	 */
	public void updateLightmap(NativeImage lightPixels, IClientLevelWrapper level)
	{
		// Using ClientLevelWrapper as the key would be better, but we don't have a consistent way to create the same
		// object for the same MC level and/or the same hash,
		// so this will have to do for now
		IDimensionTypeWrapper dimensionType = level.getDimensionType();
		
		LightMapWrapper wrapper = this.lightmapByDimensionType.computeIfAbsent(dimensionType, (dimType) -> new LightMapWrapper());
		wrapper.uploadLightmap(lightPixels);
	}
	public void setLightmapId(int tetxureId, IClientLevelWrapper level)
	{
		// Using ClientLevelWrapper as the key would be better, but we don't have a consistent way to create the same
		// object for the same MC level and/or the same hash,
		// so this will have to do for now
		IDimensionTypeWrapper dimensionType = level.getDimensionType();

		LightMapWrapper wrapper = this.lightmapByDimensionType.computeIfAbsent(dimensionType, (dimType) -> new LightMapWrapper());
		wrapper.setLightmapId(tetxureId);
	}
	
	@Override
	public float getShade(EDhDirection lodDirection)
	{
		EDhApiLodShading lodShading = Config.Client.Advanced.Graphics.Quality.lodShading.get();
		switch (lodShading)
		{
			default:
			case AUTO:
				if (MC.level != null)
				{
					Direction mcDir = McObjectConverter.Convert(lodDirection);
					return MC.level.getShade(mcDir, true);
				}
				else
				{
					return 0.0f;
				}
			
			case ENABLED:
				switch (lodDirection)
				{
					case DOWN:
						return 0.5F;
					default:
					case UP:
						return 1.0F;
					case NORTH:
					case SOUTH:
						return 0.8F;
					case WEST:
					case EAST:
						return 0.6F;
				}
			
			case DISABLED:
				return 1.0F;
		}
	}
	
	
	
}
