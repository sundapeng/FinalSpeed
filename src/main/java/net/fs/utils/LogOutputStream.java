// Copyright (c) 2015 D1SM.net
package net.fs.utils;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

public class LogOutputStream extends PrintStream{
	
	Set<LogListener> listeners=new HashSet<LogListener>();
	
	StringBuffer buffer=new StringBuffer();
	
	public LogOutputStream(OutputStream out) {
		super(out);
	}
	
	@Override
	public void write(byte[] buf, int off, int len) {
		super.write(buf, off, len);
		fireEvent(new String(buf, off, len));
	}
	
	private void fireEvent(String text){
		if(buffer!=null&&buffer.length()<10000){
			buffer.append(text);
		}
		for(LogListener listener:listeners){
			listener.onAppendContent(this,text);
		}
	}
	
	public void addListener(LogListener listener){
		listeners.add(listener);
	}
	
	public void remvoeListener(LogListener listener){
		listeners.remove(listener);
	}

	public StringBuffer getBuffer() {
		return buffer;
	}

	public void setBuffer(StringBuffer buffer) {
		this.buffer = buffer;
	}
	
	
}
