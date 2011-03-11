/**
 * JLibs: Common Utilities for Java
 * Copyright (C) 2009  Santhosh Kumar T <santhosh.tekuri@gmail.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package jlibs.core.nio;

import jlibs.core.lang.Waiter;
import jlibs.core.util.AbstractIterator;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Santhosh Kumar T
 */
public class NIOSelector extends Debuggable implements Iterable<NIOChannel>{
    private static AtomicLong ID_GENERATOR = new AtomicLong();

    protected final long id = ID_GENERATOR.incrementAndGet();
    protected final Selector selector;

    public NIOSelector(long selectTimeout) throws IOException{
        selector = Selector.open();
        setSelectTimeout(selectTimeout);
    }

    public long id(){
        return id;
    }

    protected long lastClientID;
    public ClientChannel newClient() throws IOException{
        validate();
        return new ClientChannel(this, SocketChannel.open());
    }

    private long selectTimeout;
    public long getSelectTimeout(){
        return selectTimeout;
    }

    public void setSelectTimeout(long selectTimeout){
        if(selectTimeout<=0)
            throw new IllegalArgumentException("selectTimeout should be >0");
        this.selectTimeout = selectTimeout;
    }

    @Override
    public String toString(){
        return "NIOSelector@"+id;
    }

    public void wakeup(){
        selector.wakeup();
    }

    /*-------------------------------------------------[ Shutdown ]---------------------------------------------------*/

    private volatile boolean force;
    private volatile boolean initiateShutdown;
    private boolean shutdownInProgress;

    public void shutdown(boolean force){
        if(isShutdownPending() || isShutdown())
            return;
        this.force = force;
        initiateShutdown = true;
        wakeup();
        if(DEBUG)
            println(this+".shutdownRequested");
    }

    public boolean isShutdownPending(){
        return (initiateShutdown || shutdownInProgress) && (serverCount()!=0 || connectedClients!=0 || connectionPendingClients!=0);
    }

    public boolean isShutdown(){
        return shutdownInProgress && serverCount()==0 && connectedClients==0 && connectionPendingClients==0;
    }

    protected void validate() throws IOException{
        if(isShutdownPending())
            throw new IOException("shutdown in progress");
        if(isShutdown())
            throw new IOException("already shutdown");
    }

    /*-------------------------------------------------[ ShutdownHook ]---------------------------------------------------*/

    public void shutdownOnExit(final boolean force){
        if(!isShutdown()){
            Runtime.getRuntime().addShutdownHook(new Thread(){
                @Override
                public void run(){
                    try{
                        shutdownAndWait(force);
                    }catch (InterruptedException ex){
                        ex.printStackTrace();
                    }
                }
            });
        }
    }

    private final Object shutdownLock = new Object();
    public void waitForShutdown() throws InterruptedException{
        synchronized(shutdownLock){
            if(!isShutdown())
                shutdownLock.wait();
        }
    }

    public void shutdownAndWait(boolean force) throws InterruptedException{
        synchronized(shutdownLock){
            shutdown(force);
            if(!isShutdown())
                shutdownLock.wait();
        }
    }

    /*-------------------------------------------------[ Statistics ]---------------------------------------------------*/

    protected List<ServerChannel> servers = new ArrayList<ServerChannel>();
    protected long connectionPendingClients;
    protected long connectedClients;

    public long serverCount(){
        return servers.size();
    }

    public long connectionPendingClientsCount(){
        return connectionPendingClients;
    }

    public long connectedClientsCount(){
        return connectedClients;
    }

    /*-------------------------------------------------[ Iterable ]---------------------------------------------------*/

    @Override
    public Iterator<NIOChannel> iterator(){
        return iterator;
    }

    private Iterator<NIOChannel> iterator = new AbstractIterator<NIOChannel>(){
        private Iterator<NIOChannel> delegate = Collections.<NIOChannel>emptyList().iterator();
        @Override
        protected NIOChannel computeNext(){
            try{
                while(!isShutdown() && !delegate.hasNext())
                    delegate = select();
                if(delegate.hasNext())
                    return delegate.next();
                else{
                    if(DEBUG)
                        println(NIOSelector.this+".shutdown");
                    selector.close();
                    synchronized(shutdownLock){
                        shutdownLock.notifyAll();
                    }
                    return null;
                }
            }catch(IOException ex){
                throw new RuntimeException(ex);
            }
        }
    };

    /*-------------------------------------------------[ Tasks ]---------------------------------------------------*/

    private volatile List<Runnable> tasks = new LinkedList<Runnable>();

    public synchronized void invokeLater(Runnable task){
        tasks.add(task);
        wakeup();
    }

    public void invokeAndWait(Runnable task) throws InterruptedException{
        task = new Waiter(task);
        synchronized(task){
            invokeLater(task);
            task.wait();
        }
    }

    private void runTasks(){
        List<Runnable> list;
        synchronized(this){
            list = tasks;
            tasks = new LinkedList<Runnable>();
        }
        for(Runnable task: list){
            try{
                task.run();
            }catch(Exception ex){
                ex.printStackTrace();
            }
        }
    }

    /*-------------------------------------------------[ Selection ]---------------------------------------------------*/

    protected List<NIOChannel> ready = new LinkedList<NIOChannel>();
    private Iterator<NIOChannel> select() throws IOException{
        if(ready.size()>0)
            return readyIterator.reset();
        runTasks();
        if(ready.size()>0)
            return readyIterator.reset();
        if(initiateShutdown){
            shutdownInProgress = true;
            initiateShutdown = false;
            if(DEBUG)
                println(this+".shutdownInitialized: servers="+serverCount()+" connectedClients="+connectedClientsCount()+" connectionPendingClients="+connectionPendingClientsCount());
            while(servers.size()>0)
                servers.get(0).unregister(this);
            if(force){
                for(SelectionKey key: selector.keys()){
                    try{
                        ((NIOChannel)key.attachment()).close();
                    }catch(IOException ex){
                        ex.printStackTrace();
                    }
                }
            }
        }

        if(isShutdown())
            return Collections.<NIOChannel>emptyList().iterator();
        else if(selector.select(timeoutTracker.isTracking()?selectTimeout:0)>0)
            return selectedIterator.reset();
        else
            return timeoutTracker.reset();
    }

    private ReadyIterator readyIterator = new ReadyIterator();
    private class ReadyIterator implements Iterator<NIOChannel>{
        private int count;
        public ReadyIterator reset(){
            count = ready.size();
            return this;
        }

        @Override
        public boolean hasNext(){
            return count>0;
        }

        @Override
        public NIOChannel next(){
            if(count==0)
                throw new NoSuchElementException();
            NIOChannel channel = ready.remove(0);
            count--;
            return channel;
        }

        @Override
        public void remove(){
            throw new UnsupportedOperationException();
        }
    }

    private SelectedIterator selectedIterator = new SelectedIterator();
    private class SelectedIterator extends AbstractIterator<NIOChannel>{
        private Iterator<SelectionKey> keys;

        @Override
        public SelectedIterator reset(){
            super.reset();
            keys = selector.selectedKeys().iterator();
            timeoutTracker.reset();
            return this;
        }

        @Override
        protected NIOChannel computeNext(){
            while(keys.hasNext()){
                SelectionKey key = keys.next();
                keys.remove();
                NIOChannel channel = (NIOChannel)key.attachment();
                if(key.isValid()){
                    if(channel instanceof ClientChannel){
                        ClientChannel client = (ClientChannel)channel;
                        timeoutTracker.untrack(client);
                    }
                    if(channel.process())
                        return channel;
                }
            }
            return timeoutTracker.hasNext() ? timeoutTracker.next() : null;
        }
    }
    protected final TimeoutTracker timeoutTracker = new TimeoutTracker();
}