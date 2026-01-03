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

package com.seibel.distanthorizons.common.wrappers.gui;

import net.minecraft.network.chat.Component;

import net.minecraft.client.gui.components.Button;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.resources.ResourceLocation;

/**
 * Creates a button with a texture on it (and a background) that works with all mc versions
 *
 * @author coolGi
 * @version 2023-10-03
 */
public class TexturedButtonWidget extends ImageButton
{
	public final boolean renderBackground;
	
	
	
	public TexturedButtonWidget(
		int x, int y, int width, int height, int u, int v, int hoveredVOffset,
		ResourceLocation textureResourceLocation,
		int textureWidth, int textureHeight, OnPress pressAction, Component text)
	{
		this(x, y, width, height, u, v, hoveredVOffset, textureResourceLocation, textureWidth, textureHeight, pressAction, text, true);
	}
	public TexturedButtonWidget(
		int x, int y, int width, int height, int u, int v, int hoveredVOffset,
		ResourceLocation textureResourceLocation,
		int textureWidth, int textureHeight, OnPress pressAction, Component text,
		boolean renderBackground)
	{
		super(x, y, width, height, u, v, hoveredVOffset, textureResourceLocation, textureWidth, textureHeight, pressAction, text);
		
		this.renderBackground = renderBackground;
	}
	
	@Override
	public void renderWidget(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground) // Renders the background of the button
		{
			int i = 1;
			if (!this.active)           i = 0;
			else if (this.isHovered)    i = 2;

			matrices.blit(WIDGETS_LOCATION, this.getX(), this.getY(), 0, 46 + i * 20, this.getWidth() / 2, this.getHeight());
			matrices.blit(WIDGETS_LOCATION, this.getX() + this.getWidth() / 2, this.getY(), 200 - this.width / 2, 46 + i * 20, this.getWidth() / 2, this.getHeight());
		}
		
		super.renderWidget(matrices, mouseX, mouseY, delta);
	}
	
}
