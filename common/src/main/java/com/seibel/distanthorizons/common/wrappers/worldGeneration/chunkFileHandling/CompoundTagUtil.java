package com.seibel.distanthorizons.common.wrappers.worldGeneration.chunkFileHandling;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

/**
 * these tag helpers are usedd to simplify tag accessing between MC versions
 */
public class CompoundTagUtil
{
	
	/** defaults to "false" if the tag isn't present */
	public static boolean getBoolean(CompoundTag tag, String key)
	{
		return tag.getBoolean(key);
	}
	
	/** defaults to "0" if the tag isn't present */
	public static byte getByte(CompoundTag tag, String key)
	{
		return tag.getByte(key);
	}
	
	/** defaults to "0" if the tag isn't present */
	public static short getShort(ListTag tag, int index)
	{
		return tag.getShort(index);
	}
	
	/** defaults to "0" if the tag isn't present */
	public static int getInt(CompoundTag tag, String key)
	{
		return tag.getInt(key);
	}
	
	/** defaults to "0" if the tag isn't present */
	public static long getLong(CompoundTag tag, String key)
	{
		return tag.getInt(key);
	}
	
	
	
	/** defaults to null if the tag isn't present */
	@Nullable
	public static String getString(CompoundTag tag, String key)
	{
		return tag.getString(key);
	}
	
	/** defaults to null if the tag isn't present */
	@Nullable
	public static byte[] getByteArray(CompoundTag tag, String key)
	{
		return tag.getByteArray(key);
	}
	
	
	
	/** defaults to null if the tag isn't present */
	@Nullable
	public static CompoundTag getCompoundTag(CompoundTag tag, String key)
	{
		return tag.getCompound(key);
	}
	/** defaults to null if the tag isn't present */
	@Nullable
	public static CompoundTag getCompoundTag(ListTag tag, int index)
	{
		return tag.getCompound(index);
	}
	
	/**
	 * defaults to null if the tag isn't present
	 * @param elementType unused after MC 1.21.5
	 */
	@Nullable
	public static ListTag getListTag(CompoundTag tag, String key, int elementType)
	{
		return tag.getList(key, elementType);
	}
	
	/** defaults to null if the tag isn't present */
	@Nullable
	public static ListTag getListTag(ListTag tag, int index)
	{
		return tag.getList(index);
	}
	
	
	
	public static boolean contains(CompoundTag tag, String key, int index)
	{
		return tag.contains(key, index);
	}
	
	
	
}
