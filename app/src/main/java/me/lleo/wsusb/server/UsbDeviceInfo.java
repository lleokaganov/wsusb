package me.lleo.wsusb.server;

import java.nio.ByteBuffer;

import me.lleo.wsusb.server.protocol.UsbIpDevice;
import me.lleo.wsusb.server.protocol.UsbIpInterface;

public class UsbDeviceInfo {
	public UsbIpDevice dev;
	public UsbIpInterface[] interfaces;
	
	public int getWireSize() {
		return UsbIpDevice.WIRE_LENGTH +
		(UsbIpInterface.WIRE_SIZE * dev.bNumInterfaces);
	}
	
	public byte[] serialize() {
		byte[] devSerialized = dev.serialize();
		
		ByteBuffer bb = ByteBuffer.allocate(getWireSize());
		
		bb.put(devSerialized);
		
		for (UsbIpInterface iface : interfaces) {
			bb.put(iface.serialize());
		}
		
		return bb.array();
	}
}
