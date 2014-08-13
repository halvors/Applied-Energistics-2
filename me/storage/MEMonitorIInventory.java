package appeng.me.storage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.TreeMap;

import net.minecraft.item.ItemStack;
import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.StorageFilter;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.util.InventoryAdaptor;
import appeng.util.Platform;
import appeng.util.inv.ItemSlot;

public class MEMonitorIInventory implements IMEInventory<IAEItemStack>, IMEMonitor<IAEItemStack>
{

	class CachedItemStack
	{

		public CachedItemStack(ItemStack is) {
			if ( is == null )
			{
				itemStack = null;
				aeStack = null;
			}
			else
			{
				itemStack = is.copy();
				aeStack = AEApi.instance().storage().createItemStack( is );
			}
		}

		final ItemStack itemStack;
		final IAEItemStack aeStack;
	};

	final InventoryAdaptor adaptor;

	final TreeMap<Integer, CachedItemStack> memory;
	final IItemList<IAEItemStack> list = AEApi.instance().storage().createItemList();
	final HashMap<IMEMonitorHandlerReceiver<IAEItemStack>, Object> listeners = new HashMap();

	public BaseActionSource mySource;
	public StorageFilter mode = StorageFilter.EXTACTABLE_ONLY;

	@Override
	public void addListener(IMEMonitorHandlerReceiver<IAEItemStack> l, Object verificationToken)
	{
		listeners.put( l, verificationToken );
	}

	@Override
	public void removeListener(IMEMonitorHandlerReceiver<IAEItemStack> l)
	{
		listeners.remove( l );
	}

	public MEMonitorIInventory(InventoryAdaptor adaptor) {
		this.adaptor = adaptor;
		memory = new TreeMap();
	}

	@Override
	public IAEItemStack injectItems(IAEItemStack input, Actionable type, BaseActionSource src)
	{
		ItemStack out = null;

		if ( type == Actionable.SIMULATE )
			out = adaptor.simulateAdd( input.getItemStack() );
		else
			out = adaptor.addItems( input.getItemStack() );

		onTick();

		if ( out == null )
			return null;

		// better then doing construction from scratch :3
		IAEItemStack o = input.copy();
		o.setStackSize( out.stackSize );
		return o;
	}

	@Override
	public AccessRestriction getAccess()
	{
		return AccessRestriction.READ_WRITE;
	}

	@Override
	public boolean isPrioritized(IAEItemStack input)
	{
		return false;
	}

	@Override
	public boolean canAccept(IAEItemStack input)
	{
		return true;
	}

	@Override
	public int getPriority()
	{
		return 0;
	}

	@Override
	public int getSlot()
	{
		return 0;
	}

	@Override
	public IItemList<IAEItemStack> getStorageList()
	{
		return list;
	}

	@Override
	public IItemList<IAEItemStack> getAvailableItems(IItemList out)
	{
		for (CachedItemStack is : memory.values())
			out.addStorage( is.aeStack );

		return out;
	}

	@Override
	public IAEItemStack extractItems(IAEItemStack request, Actionable type, BaseActionSource src)
	{
		ItemStack out = null;

		if ( type == Actionable.SIMULATE )
			out = adaptor.simulateRemove( (int) request.getStackSize(), request.getItemStack(), null );
		else
			out = adaptor.removeItems( (int) request.getStackSize(), request.getItemStack(), null );

		if ( out == null )
			return null;

		// better then doing construction from scratch :3
		IAEItemStack o = request.copy();
		o.setStackSize( out.stackSize );

		onTick();

		return o;
	}

	public TickRateModulation onTick()
	{
		boolean changed = false;

		list.resetStatus();
		for (ItemSlot is : adaptor)
		{
			CachedItemStack old = memory.get( is.slot );
			ItemStack newIS = is == null || is.isExtractable == false && mode == StorageFilter.EXTACTABLE_ONLY ? null : is.getItemStack();
			ItemStack oldIS = old == null ? null : old.itemStack;

			if ( isDiffrent( newIS, oldIS ) )
			{
				CachedItemStack cis = new CachedItemStack( is.getItemStack() );
				memory.put( is.slot, cis );

				if ( old != null && old.aeStack != null )
				{
					old.aeStack.setStackSize( -old.aeStack.getStackSize() );
					postDiffrence( old.aeStack );
				}

				if ( cis != null && cis.aeStack != null )
				{
					postDiffrence( cis.aeStack );
					list.add( cis.aeStack );
				}

				changed = true;
			}
			else if ( is != null )
			{
				int newSize = (newIS == null ? 0 : newIS.stackSize);
				int diff = newSize - (oldIS == null ? 0 : oldIS.stackSize);

				IAEItemStack stack = (old == null || old.aeStack == null ? AEApi.instance().storage().createItemStack( newIS ) : old.aeStack.copy());
				if ( stack != null )
				{
					stack.setStackSize( newSize );
					list.add( stack );
				}

				if ( diff != 0 && stack != null )
				{
					CachedItemStack cis = new CachedItemStack( is.getItemStack() );
					memory.put( is.slot, cis );

					IAEItemStack a = stack.copy();
					a.setStackSize( diff );
					postDiffrence( a );
					changed = true;
				}
			}
		}

		return changed ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
	}

	private boolean isDiffrent(ItemStack a, ItemStack b)
	{
		if ( a == b && b == null )
			return false;

		if ( (a == null && b != null) || (a != null && b == null) )
			return true;

		return !Platform.isSameItemPrecise( a, b );
	}

	private void postDiffrence(IAEItemStack a)
	{
		// AELog.info( a.getItemStack().getUnlocalizedName() + " @ " + a.getStackSize() );
		if ( a != null )
		{
			Iterator<Entry<IMEMonitorHandlerReceiver<IAEItemStack>, Object>> i = listeners.entrySet().iterator();
			while (i.hasNext())
			{
				Entry<IMEMonitorHandlerReceiver<IAEItemStack>, Object> l = i.next();
				IMEMonitorHandlerReceiver<IAEItemStack> key = l.getKey();
				if ( key.isValid( l.getValue() ) )
					key.postChange( this, a, mySource );
				else
					i.remove();
			}
		}
	}

	@Override
	public StorageChannel getChannel()
	{
		return StorageChannel.ITEMS;
	}

	@Override
	public boolean validForPass(int i)
	{
		return true;
	}

}
