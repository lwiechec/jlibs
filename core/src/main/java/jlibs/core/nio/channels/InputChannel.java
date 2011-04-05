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

package jlibs.core.nio.channels;

import jlibs.core.lang.ByteSequence;
import jlibs.core.lang.Bytes;
import jlibs.core.nio.AttachmentSupport;
import jlibs.core.nio.ClientChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.Iterator;

/**
 * @author Santhosh Kumar
 */
public abstract class InputChannel extends AttachmentSupport implements ReadableByteChannel{
    protected final NIOSupport nioSupport;

    protected InputChannel(NIOSupport nioSupport){
        this.nioSupport = nioSupport;
        nioSupport.attachHandler();
        nioSupport.setInput(this);
    }

    public final ClientChannel client(){
        return nioSupport.client();
    }

    public final void addInterest() throws IOException{
        if(activateInterest())
            nioSupport.addInterest();
        else if(handler!=null)
            handler.onRead(this);
    }

    protected boolean activateInterest(){
        return unread==null;
    }

    public void removeInterest() throws IOException{
        nioSupport.removeInterest();
    }

    protected InputHandler handler;
    public void setHandler(InputHandler handler){
        this.handler = handler;
    }

    private boolean eof;

    @Override
    public final int read(ByteBuffer dst) throws IOException{
        int pos = dst.position();
        if(unread!=null){
            Iterator<ByteSequence> sequences = unread.iterator();
            while(sequences.hasNext()){
                ByteSequence seq = sequences.next();
                int remaining = Math.min(dst.remaining(), seq.length());
                System.arraycopy(seq.buffer(), seq.offset(), dst.array(), dst.arrayOffset() + dst.position(), remaining);
                dst.position(dst.position()+remaining);
                if(remaining==seq.length())
                    sequences.remove();
                else{
                    unread.remove(remaining);
                    return dst.position()-pos;
                }
            }
            if(unread.isEmpty())
                unread = null;
        }
        int read = 0;
        if(dst.hasRemaining())
            read = doRead(dst);
        int result = dst.position() == pos && read == -1 ? -1 : dst.position() - pos;
        eof = result==-1;
        return result;
    }
    protected abstract int doRead(ByteBuffer dst) throws IOException;
    public boolean isEOF(){
        return eof;
    }

    protected Bytes unread;
    public final void unread(byte buff[], int offset, int length, boolean clone){
        if(length==0)
            return;
        eof = false;
        if(unread==null)
            unread = new Bytes();
        if(clone){
            buff = Arrays.copyOfRange(buff, offset, offset + length);
            offset = 0;
        }
        unread.prepend(new ByteSequence(buff, offset, length));
    }

    public long pending(){
        return unread==null ? 0 : unread.size();
    }

    private boolean closed;

    @Override
    public final boolean isOpen(){
        return !closed;
    }

    @Override
    public void close(){
        closed = true;
        unread = null;
    }
}