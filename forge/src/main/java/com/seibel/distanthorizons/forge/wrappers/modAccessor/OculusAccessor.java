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

package com.seibel.distanthorizons.forge.wrappers.modAccessor;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class OculusAccessor implements IIrisAccessor
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();

	private static Object irisApiInstance;
	private static MethodHandle isShaderPackInUseMethod;
	private static MethodHandle isRenderingShadowPassMethod;
	private static boolean apiAvailable = false;


	public OculusAccessor()
	{
		try
		{
			Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			MethodHandle getInstanceMethod = MethodHandles.lookup().findStatic(
					irisApiClass, "getInstance", MethodType.methodType(irisApiClass));
			irisApiInstance = getInstanceMethod.invoke();

			isShaderPackInUseMethod = MethodHandles.lookup().findVirtual(
					irisApiClass, "isShaderPackInUse", MethodType.methodType(boolean.class));
			isRenderingShadowPassMethod = MethodHandles.lookup().findVirtual(
					irisApiClass, "isRenderingShadowPass", MethodType.methodType(boolean.class));

			apiAvailable = true;
			LOGGER.info("Oculus support enabled.");
		}
		catch (Throwable e)
		{
			LOGGER.warn("Failed to initialize Oculus API access: " + e.getMessage());
			apiAvailable = false;
		}
	}



	@Override
	public String getModName()
	{
		return "oculus";
	}

	@Override
	public boolean isShaderPackInUse()
	{
		if (!apiAvailable)
		{
			return true; // Assume shaders are active if we can't check
		}

		try
		{
			return (boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
		}
		catch (Throwable e)
		{
			LOGGER.warn("Error calling isShaderPackInUse: " + e.getMessage());
			return true;
		}
	}

	@Override
	public boolean isRenderingShadowPass()
	{
		if (!apiAvailable)
		{
			return false;
		}

		try
		{
			return (boolean) isRenderingShadowPassMethod.invoke(irisApiInstance);
		}
		catch (Throwable e)
		{
			LOGGER.warn("Error calling isRenderingShadowPass: " + e.getMessage());
			return false;
		}
	}

}
