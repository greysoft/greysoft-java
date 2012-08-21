/*
 * Copyright 2010-2012 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.naf.reactor;

public final class ConcurrentListener
	extends Listener
{
	/**
	 * Servers that are spawned by the ConcurrentListener must be subclasses of this base class.
	 * <br/>
	 * In addition to overriding its explicit methods, the subclass must also provide a constructor
	 * with this signature:<br/>
	 * <code>classname(com.grey.naf.reactor.ConcurrentListener, com.grey.base.config.XmlConfig)</code><br/>
	 * That subclass constructor must in turn call the
	 * <code>Server(ConcurrentListener)</code>
	 * constructor shown below.
	 */
	public static abstract class Server
		extends ChannelMonitor
		implements com.grey.base.utils.PrototypeFactory.PrototypeObject
	{
		public final ConcurrentListener lstnr;
		public Server(ConcurrentListener l) {super(l.dsptch); lstnr=l;}
		public boolean stopServer() {return false;}
	}

	private final Server protoServer;  // connection handler spawned by this Listener (so Listener is responsible for it)
	private final Class<?> serverType;
	private final com.grey.base.utils.HashedSet<Server> activeservers = new com.grey.base.utils.HashedSet<Server>();
	private final com.grey.base.utils.ObjectWell<Server> spareservers;

	@Override
	public Class<?> getServerType() {return serverType;}

	public ConcurrentListener(String lname, com.grey.naf.reactor.Dispatcher d, Object controller,
			com.grey.base.config.XmlConfig cfg, Class<?> srvclass, String iface, int port)
					throws com.grey.base.GreyException, java.io.IOException
	{
		this(lname, d, controller, cfg, makeDefaults(srvclass, iface, port));
	}

	public ConcurrentListener(String lname, com.grey.naf.reactor.Dispatcher d, Object controller,
			com.grey.base.config.XmlConfig cfg, java.util.Map<String,Object> cfgdflts)
					throws com.grey.base.GreyException, java.io.IOException
	{
		super(lname, d, controller, cfg, cfgdflts);

		// So far, we've only read attributes from the main config block. Check if this Listener's config is linked to another's, before
		// delving into the contents of its Server config block.
		if (cfg != null) {
			String linkname = cfg.getValue("@configlink", false, null);
			if (linkname != null) cfg = new com.grey.base.config.XmlConfig(cfg, "../listener[@name='"+linkname+"']");
		}
		Class<?> srvclss = (cfgdflts == null ? null : (Class<?>)cfgdflts.get(CFGMAP_CLASS));
		com.grey.base.config.XmlConfig servercfg = (cfg == null ? null : new com.grey.base.config.XmlConfig(cfg, "server"));
		Object s = dsptch.nafcfg.createEntity(servercfg, srvclss, Server.class, false,
				new Class<?>[]{ConcurrentListener.class, com.grey.base.config.XmlConfig.class},
				new Object[]{this, servercfg});
		protoServer = Server.class.cast(s);
		serverType = protoServer.getClass();

		int srvmin = getInt(cfgdflts, CFGMAP_INITSPAWN, 0);
		int srvmax = getInt(cfgdflts, CFGMAP_MAXSPAWN, 0);
		int srvincr = getInt(cfgdflts, CFGMAP_INCRSPAWN, 0);
		if (cfg != null) {
			srvmin = cfg.getInt("@initservers", false, srvmin);
			srvmax = cfg.getInt("@maxservers", false, srvmax);
			srvincr = cfg.getInt("@incrservers", false, srvincr);
		}
		com.grey.base.utils.PrototypeFactory fact = new com.grey.base.utils.PrototypeFactory(protoServer);
		spareservers = new com.grey.base.utils.ObjectWell<Server>(protoServer.getClass(), fact, srvmin, srvmax, srvincr);

		log.info("Listener="+name+": Server is "+protoServer.getClass().getName()
				+" - init/max/incr="+spareservers.size()+"/"+srvmax+"/"+srvincr);
	}

	@Override
	public boolean stopListener()
	{
		int cnt = activeservers.size();
		java.util.Iterator<Server> it = activeservers.iterator();
		while (it.hasNext()) {
			Server cep = it.next();
			if (cep.stopServer()) it.remove();
		}
		log.info("Listener="+name+" has stopped "+(cnt - activeservers.size())+"/"+cnt+" servers");
		return (activeservers.size() == 0);
	}

	@Override
	protected void listenerStopped()
	{
		protoServer.stopServer();
		spareservers.clear();
	}

	@Override
	public void entityStopped(Object obj)
	{
		Server srvr = Server.class.cast(obj);
		activeservers.remove(srvr);
		spareservers.store(srvr);

		if (inShutdown && activeservers.size() == 0) {
			stopped(true);
			return;
		}
	}

	// NB: Can't loop on SelectionKey.isAcceptable(), as it will remain True until we return to Dispatcher
	@Override
	protected void connectionReceived() throws java.io.IOException
	{
		java.nio.channels.ServerSocketChannel srvsock = (java.nio.channels.ServerSocketChannel)iochan;
		java.nio.channels.SocketChannel connsock;

		while ((connsock = srvsock.accept()) != null) {
			try {
				handleConnection(connsock);
			} catch (Throwable ex) {
				log.info("Listener="+name+": Error fielding connection", ex);
			}
		}
	}

	private void handleConnection(java.nio.channels.SocketChannel connsock) throws com.grey.base.FaultException, java.io.IOException
	{
		ChannelMonitor srvr = spareservers.extract();
		if (srvr == null) {
			// we're at max capacity - can't allocate any more server objects
			log.info("Listener=" + name + " dropping connection - no spare servers");
			connsock.close();
			return;
		}
		activeservers.add((Server)srvr);
		srvr.accepted(connsock, this);
	}
}
