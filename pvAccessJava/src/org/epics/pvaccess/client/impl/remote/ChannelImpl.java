/*
 * Copyright (c) 2004 by Cosylab
 *
 * The full license specifying the redistribution, modification, usage and other
 * rights and obligations is included with the distribution of this project in
 * the file "LICENSE-CAJ". If the license is not included visit Cosylab web site,
 * <http://www.cosylab.com>.
 *
 * THIS SOFTWARE IS PROVIDED AS-IS WITHOUT WARRANTY OF ANY KIND, NOT EVEN THE
 * IMPLIED WARRANTY OF MERCHANTABILITY. THE AUTHOR OF THIS SOFTWARE, ASSUMES
 * _NO_ RESPONSIBILITY FOR ANY CONSEQUENCE RESULTING FROM THE USE, MODIFICATION,
 * OR REDISTRIBUTION OF THIS SOFTWARE.
 */

package org.epics.pvaccess.client.impl.remote;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.epics.pvaccess.CAConstants;
import org.epics.pvaccess.CAException;
import org.epics.pvaccess.PVFactory;
import org.epics.pvaccess.client.AccessRights;
import org.epics.pvaccess.client.Channel;
import org.epics.pvaccess.client.ChannelArray;
import org.epics.pvaccess.client.ChannelArrayRequester;
import org.epics.pvaccess.client.ChannelGet;
import org.epics.pvaccess.client.ChannelGetRequester;
import org.epics.pvaccess.client.ChannelProcess;
import org.epics.pvaccess.client.ChannelProcessRequester;
import org.epics.pvaccess.client.ChannelProvider;
import org.epics.pvaccess.client.ChannelPut;
import org.epics.pvaccess.client.ChannelPutGet;
import org.epics.pvaccess.client.ChannelPutGetRequester;
import org.epics.pvaccess.client.ChannelPutRequester;
import org.epics.pvaccess.client.ChannelRPC;
import org.epics.pvaccess.client.ChannelRPCRequester;
import org.epics.pvaccess.client.ChannelRequester;
import org.epics.pvaccess.client.GetFieldRequester;
import org.epics.pvaccess.client.impl.remote.search.SearchInstance;
import org.epics.pvaccess.impl.remote.Transport;
import org.epics.pvaccess.impl.remote.TransportClient;
import org.epics.pvaccess.impl.remote.TransportSendControl;
import org.epics.pvaccess.impl.remote.TransportSender;
import org.epics.pvaccess.impl.remote.request.ResponseRequest;
import org.epics.pvaccess.impl.remote.request.SubscriptionRequest;
import org.epics.pvdata.misc.SerializeHelper;
import org.epics.pvdata.monitor.Monitor;
import org.epics.pvdata.monitor.MonitorRequester;
import org.epics.pvdata.pv.MessageType;
import org.epics.pvdata.pv.PVField;
import org.epics.pvdata.pv.PVStructure;
import org.epics.pvdata.pv.Status;
import org.epics.pvdata.pv.Status.StatusType;
import org.epics.pvdata.pv.StatusCreate;

/**
 * Implementation of CAJ JCA <code>Channel</code>.
 * @author <a href="mailto:matej.sekoranjaATcosylab.com">Matej Sekoranja</a>
 * @version $Id$
 */
public class ChannelImpl implements Channel, SearchInstance, TransportClient, TransportSender {
	
	/**
	 * Client channel ID.
	 */
	protected final int channelID;

	/**
	 * Channel name.
	 */
	protected final String name;

	/**
	 * Context.
	 */
	protected final ClientContextImpl context;

	/**
	 * Process priority.
	 */
	protected final short priority;

	/**
	 * List of fixed addresses, if <code<null</code> name resolution will be used.
	 */
	protected final InetSocketAddress[] addresses;
	
	/**
	 * Last reported connection status.
	 */
	//protected boolean lastReportedConnectionState = false;

	/**
	 * Connection status.
	 */
	protected ConnectionState connectionState = ConnectionState.NEVER_CONNECTED;

	/**
	 * Channel requester.
	 */
	protected final ChannelRequester requester;

	/**
	 * List of all channel's pending requests (keys are subscription IDs). 
	 */
	protected final Map<Integer, ResponseRequest> responseRequests = new HashMap<Integer, ResponseRequest>();
	
	/**
	 * Allow reconnection flag. 
	 */
	protected boolean allowCreation = true;

	/**
	 * Reference counting.
	 * NOTE: synced on <code>this</code>. 
	 */
	protected int references = 1;

	/* ****************** */
	/* CA protocol fields */ 
	/* ****************** */

	/**
	 * Server transport.
	 */
	protected Transport transport = null;

	/**
	 * Server channel ID.
	 */
	protected int serverChannelID = 0xFFFFFFFF;

	/**
	 * User value used by SearchInstance.
	 */
	private final AtomicInteger userValue = new AtomicInteger();

	/* ****************** */

	/**
	 * Constructor.
	 * @param context
	 * @param name
	 * @param listener
	 * @throws CAException
	 */
	protected ChannelImpl(ClientContextImpl context, int channelID, String name,
			ChannelRequester requester, short priority, InetSocketAddress[] addresses) throws CAException
	{
		this.context = context;
		this.channelID = channelID;
		this.name = name;
		this.priority = priority;
		this.addresses = addresses;
		this.requester = requester;
		
		// register before issuing search request
		context.registerChannel(this);
		
		// connect
		connect();
	}

	/**
	 * Create a channel, i.e. submit create channel request to the server.
	 * This method is called after search is complete.
	 * @param transport
	 */
	public synchronized void createChannel(Transport transport) 
	{

		// do not allow duplicate creation to the same transport
		if (!allowCreation)
			return;
		allowCreation = false;
		
		// check existing transport
		if (this.transport != null && this.transport != transport)
		{
			disconnectPendingIO(false);
			this.transport.release(this);
		}
		else if (this.transport == transport)
		{
			// request to sent create request to same transport, ignore
			// this happens when server is slower (processing search requests) than client generating it
			return;
		}
		
		this.transport = transport;
		this.transport.enqueueSendRequest(this);
	}
	
	/**
	 * @see org.epics.pvaccess.impl.remote.request.ResponseRequest#cancel()
	 */
	public void cancel() {
		// noop
	}

	/**
	 * @see org.epics.pvaccess.impl.remote.request.ResponseRequest#timeout()
	 */
	public void timeout() {
		createChannelFailed();
	}

	/**
	 * Create channel failed.
	 */
	public synchronized void createChannelFailed()
	{
		cancel();
		// ... and search again
		initiateSearch();
	}

	/**
	 * Called when channel created succeeded on the server.
	 * <code>sid</code> might not be valid, this depends on protocol revision.
	 * @param sid
	 * @throws IllegalStateException
	 */
	public synchronized void connectionCompleted(int sid/*,  rights*/) 
		throws IllegalStateException
	{
		try
		{
			// do this silently
			if (connectionState == ConnectionState.DESTROYED)
				return;

			// store data
			this.serverChannelID = sid;
			//setAccessRights(rights);

			// user might create monitors in listeners, so this has to be done before this can happen
			// however, it would not be nice if events would come before connection event is fired
			// but this cannot happen since transport (TCP) is serving in this thread 
			resubscribeSubscriptions();
			setConnectionState(ConnectionState.CONNECTED);
		}
		finally
		{
			// end connection request
			cancel();
		}
	}

	/**
	 * @param force force destruction regardless of reference count
	 * @throws CAException
	 * @throws IllegalStateException
	 */
	public synchronized void destroy(boolean force) throws CAException, IllegalStateException {
		
		if (connectionState == ConnectionState.DESTROYED)
			throw new IllegalStateException("Channel already destroyed.");
			
		// do destruction via context
		context.destroyChannel(this, force);
		
	}

	/**
	 * Increment reference.
	 */
	public synchronized void acquire() {
		references++;
	}

	/**
	 * Actual destroy method, to be called <code>CAJContext</code>.
	 * @param force force destruction regardless of reference count
	 * @throws CAException
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public synchronized void destroyChannel(boolean force) throws CAException, IllegalStateException, IOException {

		if (connectionState == ConnectionState.DESTROYED)
			throw new IllegalStateException("Channel already destroyed.");

		references--;
		if (references > 0 && !force)
			return;
		
		// stop searching...
		context.getChannelSearchManager().unregister(this);
		cancel();

		disconnectPendingIO(true);

		if (connectionState == ConnectionState.CONNECTED)
		{
			disconnect(false, true);
		}
		else if (transport != null)
		{
			// unresponsive state, do not forget to release transport
			transport.release(this);
			transport = null;
		}

		setConnectionState(ConnectionState.DESTROYED);
		
		// unregister
		context.unregisterChannel(this);

		/*
		synchronized (accessRightsListeners)
		{
			accessRightsListeners.clear();
		}
		*/
				
		/*
		// this makes problem to the queued dispatchers...
		synchronized (connectionListeners)
		{
			connectionListeners.clear();
		}
		*/
	}

	/**
	 * Disconnected notification.
	 * @param initiateSearch	flag to indicate if searching (connect) procedure should be initiated
	 * @param remoteDestroy		issue channel destroy request.
	 */
	public synchronized void disconnect(boolean initiateSearch, boolean remoteDestroy) {
//System.err.println("CHANNEL disconnect");
		
		if (connectionState != ConnectionState.CONNECTED)
			return;
			
		if (!initiateSearch) {
			// stop searching...
			context.getChannelSearchManager().unregister(this);
			cancel();
		}
		setConnectionState(ConnectionState.DISCONNECTED);

		disconnectPendingIO(false);

		// release transport
		if (transport != null)
		{
			if (remoteDestroy) {
				issueCreateMessage = false;
				transport.enqueueSendRequest(this);
			}
			
			transport.release(this);
			transport = null;
		}
		
		if (initiateSearch)
			initiateSearch();

	}
	
	/**
	 * Initiate search (connect) procedure.
	 */
	public synchronized void initiateSearch()
	{
		allowCreation = true;
		
		if (addresses == null)
			context.getChannelSearchManager().register(this);
		else
			// TODO not only first
			// TODO minor version
			// TODO what to do if there is no channel, do not search in a loop!!! do this in other thread...!
			searchResponse(CAConstants.CA_PROTOCOL_REVISION, addresses[0]);
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.impl.remote.search.SearchInstance#getUserValue()
	 */
	@Override
	public AtomicInteger getUserValue() {
		return userValue;
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.impl.remote.ChannelSearchManager.SearchInstance#searchResponse(byte, java.net.InetSocketAddress)
	 */
	@Override
	public synchronized void searchResponse(byte minorRevision, InetSocketAddress serverAddress) {
		// channel is already automatically unregistered
		
		Transport transport = getTransport();
		if (transport != null)
		{
			// multiple defined PV or reconnect request (same server address)
			if (!transport.getRemoteAddress().equals(serverAddress))
			{
				requester.message("More than one channel with name '" + name +
							 "' detected, additional response from: " + serverAddress, MessageType.warning);
				return;
			}
		}
		
		transport = context.getTransport(this, serverAddress, minorRevision, priority);
		if (transport == null)
		{
			createChannelFailed();
			return;
		}

		// create channel
		createChannel(transport);
	}

	/**
	 * @see org.epics.pvaccess.impl.remote.TransportClient#transportClosed()
	 */
	public void transportClosed() {
//System.err.println("CHANNEL transportClosed");
		disconnect(true, false);
	}

	/**
	 * @see org.epics.pvaccess.impl.remote.TransportClient#transportChanged()
	 */
	public /*synchronized*/ void transportChanged() {
//System.err.println("CHANNEL transportChanged");
// this will be called immediately after reconnect... bad...
		/*
		if (connectionState == ConnectionState.CONNECTED)
		{
			disconnect(true, false);
		}
		*/
	}

	/**
	 * @see org.epics.pvaccess.impl.remote.TransportClient#transportResponsive(org.epics.pvaccess.impl.remote.Transport)
	 */
	public synchronized void transportResponsive(Transport transport) {
//System.err.println("CHANNEL transportResponsive");
		if (connectionState == ConnectionState.DISCONNECTED)
		{
			updateSubscriptions();
			
			// reconnect using existing IDs, data
			connectionCompleted(serverChannelID/*, accessRights*/);
		}
	}

	/**
	 * @see org.epics.pvaccess.impl.remote.TransportClient#transportUnresponsive()
	 */
	public synchronized void transportUnresponsive() {
//		System.err.println("CHANNEL transportUnresponsive");
		//if (connectionState == ConnectionState.CONNECTED)
		{
			// TODO 2 types of disconnected state - distinguish them otherwise disconnect will handle connection loss right
			// setConnectionState(ConnectionState.DISCONNECTED);
			// should we notify client at all?
		}
	}

	/**
	 * Set connection state and if changed, notifies listeners.
	 * @param newState	state to set.
	 */
	private synchronized void setConnectionState(ConnectionState connectionState)
	{
		if (this.connectionState != connectionState)
		{
			this.connectionState = connectionState;
			
			try
			{
				requester.channelStateChange(this, connectionState);
			}
			catch (Throwable th)
			{
				// guard CA code from exceptions
				Writer writer = new StringWriter();
				PrintWriter printWriter = new PrintWriter(writer);
				th.printStackTrace(printWriter);
				requester.message("Unexpected exception caught: " + writer, MessageType.fatalError);
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#getConnectionState()
	 */
	@Override
	public synchronized ConnectionState getConnectionState() {
		return connectionState;
	}

	/**
	 * NOTE: synchronization guarantees that <code>transport</code> is non-<code>null</code> and <code>state == CONNECTED</code>.
	 * @see org.epics.pvaccess.client.Channel#getRemoteAddress()
	 */
	@Override
	public synchronized String getRemoteAddress() {
		if (connectionState != ConnectionState.CONNECTED)
			return null;
		else
			return transport.getRemoteAddress().toString();
	}

	/**
	 * Get client channel ID.
	 * @return client channel ID.
	 */
	public int getChannelID() {
		return channelID;
	}

	/**
	 * Get context.
	 */
	public ClientContextImpl getContext() {
		return context;
	}

	/**
	 * Checks if channel is in connected state,
	 * if not throws <code>IllegalStateException</code> if not.
	 *
	private final void connectionRequiredCheck()
	{
		if (connectionState != ConnectionState.CONNECTED)
			throw new IllegalStateException("Channel not connected.");
	}*/

	/**
	 * Checks if channel is in connected state and returns transport.
	 * @throws IllegalStateException if not connected.
	 */
	public synchronized final Transport checkAndGetTransport()
	{
		if (connectionState == ConnectionState.DESTROYED)
			throw new IllegalStateException("Channel destroyed.");
		else if (connectionState != ConnectionState.CONNECTED)
			throw new IllegalStateException("Channel not connected.");
		return transport;		// TODO transport can be null !!!!!!!!!!
	}

	/**
	 * Checks if channel is in connected or disconnected state,
	 * if not throws <code>IllegalStateException</code> if not.
	 *
	private final void checkState()
	{
		// connectionState is always non-null
		if (connectionState != ConnectionState.CONNECTED && connectionState != ConnectionState.DISCONNECTED)
			throw new IllegalStateException("Channel not in connected or disconnected state, state = '" + connectionState.name() + "'.");
	}*/
	
	/**
	 * Checks if channel is not it closed state.
	 * if not throws <code>IllegalStateException</code> if not.
	 *
	private final synchronized void checkNotDestroyed()
	{
		if (connectionState == ConnectionState.DESTROYED)
			throw new IllegalStateException("Channel destroyed.");
	}*/

	/**
	 * Get transport used by this channel.
	 * @return transport used by this channel.
	 */
	public synchronized Transport getTransport() {
		return transport;
	}

	/**
	 * Get SID.
	 * @return SID.
	 */
	public synchronized int getServerChannelID() {
		return serverChannelID;
	}

	/** 
	 * Register a response request.
	 * @param responseRequest response request to register.
	 */
	public void registerResponseRequest(ResponseRequest responseRequest)
	{
		synchronized (responseRequests)
		{
			responseRequests.put(responseRequest.getIOID(), responseRequest);
		}
	}

	/* 
	 * Unregister a response request.
	 * @param responseRequest response request to unregister.
	 */
	public void unregisterResponseRequest(ResponseRequest responseRequest)
	{
		synchronized (responseRequests)
		{
			responseRequests.remove(responseRequest.getIOID());
		}
	}
	
	private boolean needSubscriptionUpdate = false;
	
    private static final StatusCreate statusCreate = PVFactory.getStatusCreate();
	public static final Status channelDestroyed = statusCreate.createStatus(StatusType.WARNING, "channel destroyed", null);
	public static final Status channelDisconnected = statusCreate.createStatus(StatusType.WARNING, "channel disconnected", null);
	
	/**
	 * Disconnects (destroys) all channels pending IO.
	 * @param destroy	<code>true</code> if channel is being destroyed.
	 */
	private void disconnectPendingIO(boolean destroy)
	{
		// TODO destroy????!!
		Status status;
		if (destroy)
			status = channelDestroyed;
		else
			status = channelDisconnected;
			
		synchronized (responseRequests)
		{
			needSubscriptionUpdate = true;
			
			ResponseRequest[] rrs = new ResponseRequest[responseRequests.size()];
			responseRequests.values().toArray(rrs);
			for (int i = 0; i < rrs.length; i++)
			{
				try
				{
					rrs[i].reportStatus(status);
				}
				catch (Throwable th)
				{
					// TODO remove
					th.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * Resubscribe subscriptions. 
	 */
	// TODO to be called from non-transport thread !!!!!!
	private void resubscribeSubscriptions()
	{
		synchronized (responseRequests)
		{
			// sync get
			Transport transport = getTransport();
			
			ResponseRequest[] rrs = new ResponseRequest[responseRequests.size()];
			responseRequests.values().toArray(rrs);
			for (int i = 0; i < rrs.length; i++)
			{
				try
				{
					if (rrs[i] instanceof SubscriptionRequest)
						((SubscriptionRequest)rrs[i]).resubscribeSubscription(transport);
				}
				catch (Throwable th)
				{
					// TODO remove
					th.printStackTrace();
				}
			}
		}
	}

	/**
	 * Update subscriptions. 
	 */
	// TODO to be called from non-transport thread !!!!!!
	private void updateSubscriptions()
	{
		synchronized (responseRequests)
		{
			if (needSubscriptionUpdate)
				needSubscriptionUpdate = false;
			else
				return;	// noop

			ResponseRequest[] rrs = new ResponseRequest[responseRequests.size()];
			responseRequests.values().toArray(rrs);
			for (int i = 0; i < rrs.length; i++)
			{
				try
				{
					if (rrs[i] instanceof SubscriptionRequest)
						((SubscriptionRequest)rrs[i]).updateSubscription();
				}
				catch (Throwable th)
				{
					// TODO remove
					th.printStackTrace();
				}
			}
		}
	}

	/**
	 * Get process priority.
	 * @return process priority.
	 */
	public short getPriority() {
		return priority;
	}


	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	public synchronized String toString()
	{
		StringBuilder buffy = new StringBuilder();
		buffy.append("CHANNEL  : ").append(name).append('\n');
		buffy.append("STATE    : ").append(connectionState).append('\n');
		if (connectionState == ConnectionState.CONNECTED)
		{
			buffy.append("ADDRESS  : ").append(getRemoteAddress()).append('\n');
			//buffy.append("RIGHTS   : ").append(getAccessRights()).append('\n');
		}
		return buffy.toString();
	}
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
    

	protected synchronized final void connect() {
		// if not destroyed...
		if (connectionState == ConnectionState.DESTROYED)
			throw new IllegalArgumentException("Channel destroyed.");
		else if (connectionState != ConnectionState.CONNECTED)
			initiateSearch();
	}

	protected synchronized void disconnect() {
		// if not destroyed...
		if (connectionState == ConnectionState.DESTROYED)
			throw new IllegalArgumentException("Channel destroyed.");
		else if (connectionState == ConnectionState.CONNECTED)
			disconnect(false, true);
}

	@Override
	public ChannelGet createChannelGet(
			ChannelGetRequester channelGetRequester,
			PVStructure pvRequest) {
		return new ChannelGetRequestImpl(this, channelGetRequester, pvRequest);
	}

	@Override
	public Monitor createMonitor(
			MonitorRequester monitorRequester, PVStructure pvRequest) {
    	return new ChannelMonitorImpl(this, monitorRequester, pvRequest);	
	}

	@Override
	public ChannelProcess createChannelProcess(
			ChannelProcessRequester channelProcessRequester,
			PVStructure pvRequest) {
		return new ChannelProcessRequestImpl(this, channelProcessRequester, pvRequest);
	}
	
	@Override
	public ChannelPut createChannelPut(
			ChannelPutRequester channelPutRequester,
			PVStructure pvRequest) {
    	return new ChannelPutRequestImpl(this, channelPutRequester, pvRequest);
	}

	@Override
	public ChannelPutGet createChannelPutGet(
			ChannelPutGetRequester channelPutGetRequester,
			PVStructure pvRequest) {
    	return new ChannelPutGetRequestImpl(this, channelPutGetRequester, pvRequest);	
	}

	@Override
	public ChannelRPC createChannelRPC(ChannelRPCRequester channelRPCRequester, PVStructure pvRequest) {
    	return new ChannelRPCRequestImpl(this, channelRPCRequester, pvRequest);	
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#getAccessRights(org.epics.pvdata.pv.PVField)
	 */
	@Override
	public org.epics.pvaccess.client.AccessRights getAccessRights(PVField pvField) {
		// TODO not implemented
		return AccessRights.readWrite;
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#getChannelName()
	 */
	@Override
	public String getChannelName() {
		return name;
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#getChannelRequester()
	 */
	@Override
	public ChannelRequester getChannelRequester() {
		return requester;
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#getProvider()
	 */
	@Override
	public ChannelProvider getProvider() {
		return context.getProvider();
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#isConnected()
	 */
	@Override
	public synchronized boolean isConnected() {
		return connectionState == ConnectionState.CONNECTED;
	}

	/* (non-Javadoc)
	 * @see org.epics.pvdata.pv.Requester#getRequesterName()
	 */
	@Override
	public String getRequesterName() {
		return requester.getRequesterName();
	}

	/* (non-Javadoc)
	 * @see org.epics.pvdata.pv.Requester#message(java.lang.String, org.epics.pvdata.pv.MessageType)
	 */
	@Override
	public void message(String message, MessageType messageType) {
		// TODO
		System.err.println("[" + messageType + "] " + message);
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#destroy()
	 */
	@Override
	public void destroy() {
		try {
			destroy(false);
		} catch (IllegalStateException ise) {
			// noop on multiple destroys
		} catch (Throwable th) {
			throw new RuntimeException("Failed to destroy channel.", th);
		}
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#createChannelArray(org.epics.pvaccess.client.ChannelArrayRequester, java.lang.String, org.epics.pvdata.pv.PVStructure)
	 */
	@Override
	public ChannelArray createChannelArray(
			ChannelArrayRequester channelArrayRequester,
			PVStructure pvRequest) {
		return new ChannelArrayRequestImpl(this, channelArrayRequester, pvRequest);
	}
    
	/* (non-Javadoc)
	 * @see org.epics.pvaccess.client.Channel#getField(org.epics.pvaccess.client.GetFieldRequester, java.lang.String)
	 */
	@Override
	public void getField(GetFieldRequester requester, String subField) {
		new ChannelGetFieldRequestImpl(this, requester, subField);
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.impl.remote.TransportSender#lock()
	 */
	@Override
	public void lock() {
		// noop
	}

	private volatile boolean issueCreateMessage = true;
	
	/* (non-Javadoc)
	 * @see org.epics.pvaccess.impl.remote.TransportSender#send(java.nio.ByteBuffer, org.epics.pvaccess.impl.remote.TransportSendControl)
	 */
	@Override
	public void send(ByteBuffer buffer, TransportSendControl control) {
		if (issueCreateMessage)
		{
			control.startMessage((byte)7, (Short.SIZE+Integer.SIZE)/Byte.SIZE);
			
			// count
			buffer.putShort((short)1);
			// array of CIDs and names
			buffer.putInt(channelID);
			SerializeHelper.serializeString(name, buffer, control);
			// send immediately
			// TODO really?
			control.flush(true);
		}
		else
		{
			control.startMessage((byte)8, 2*Integer.SIZE/Byte.SIZE);
			// SID
			buffer.putInt(getServerChannelID());
			// CID
			buffer.putInt(channelID);
			// send immediately
			// TODO really?
			control.flush(true);
		}
	}

	/* (non-Javadoc)
	 * @see org.epics.pvaccess.impl.remote.TransportSender#unlock()
	 */
	@Override
	public void unlock() {
		// noop
	}
	
	

}
