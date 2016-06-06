/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package net.fs.cap;

import net.fs.rudp.Route;
import net.fs.utils.ByteShortConvert;
import net.fs.utils.MLog;

import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.PacketListener;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.PcapNetworkInterface.PromiscuousMode;
import org.pcap4j.core.PcapStat;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.EthernetPacket.EthernetHeader;
import org.pcap4j.packet.IllegalPacket;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV4Packet.IpV4Header;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.TcpPacket.TcpHeader;
import org.pcap4j.util.MacAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;


public class CapEnv {

	public MacAddress gateway_mac;

	public MacAddress local_mac;

	Inet4Address local_ipv4;
	
	public PcapHandle sendHandle;
	
	VDatagramSocket vDatagramSocket;
	
	String testIp_tcp="";
	
	String testIp_udp="5.5.5.5";
	
	String selectedInterfaceName=null;
	
	String selectedInterfaceDes="";

	PcapNetworkInterface nif;

	private  final int COUNT=-1;

	private  final int READ_TIMEOUT=1; 

	private  final int SNAPLEN= 10*1024; 

	HashMap<Integer, TCPTun> tunTable=new HashMap<Integer, TCPTun>();

	LinkedBlockingQueue<Packet> packetList=new LinkedBlockingQueue<Packet>();
	
	Random random=new Random();

	boolean client=false;
	
	short listenPort;
	
	TunManager tcpManager=null;
	
	CapEnv capEnv;
	
	Thread versinMonThread;
	
	boolean detect_by_tcp=true;
	
	public boolean tcpEnable=false;
	
	public boolean fwSuccess=true;
	
	boolean ppp=false;
	
	{
		capEnv=this;
	}
	
	public CapEnv(boolean isClient,boolean fwSuccess){
		this.client=isClient;
		this.fwSuccess=fwSuccess;
		tcpManager=new TunManager(this);
	}

	public void init() throws Exception{
		initInterface();
		Thread thread_process=new Thread(){

			public void run(){
				while(true){
					try {
						Packet packet=packetList.take();
						EthernetPacket packet_eth=(EthernetPacket) packet;
						EthernetHeader head_eth=packet_eth.getHeader();
						
						IpV4Packet ipV4Packet=null;
						if(ppp){
							ipV4Packet=getIpV4Packet_pppoe(packet_eth);
						}else {
							if(packet_eth.getPayload() instanceof IpV4Packet){
								ipV4Packet=(IpV4Packet) packet_eth.getPayload();
							}
						}
						if(ipV4Packet!=null){
							IpV4Header ipV4Header=ipV4Packet.getHeader();
							if(ipV4Packet.getPayload() instanceof TcpPacket){
								TcpPacket tcpPacket=(TcpPacket) ipV4Packet.getPayload();
								TcpHeader tcpHeader=tcpPacket.getHeader();
								if(client){
									TCPTun conn=tcpManager.getTcpConnection_Client(ipV4Header.getSrcAddr().getHostAddress(),tcpHeader.getSrcPort().value(), tcpHeader.getDstPort().value());
									if(conn!=null){
										conn.process_client(capEnv,packet,head_eth,ipV4Header,tcpPacket,false);
									}
								}else {
									TCPTun conn=null;conn = tcpManager.getTcpConnection_Server(ipV4Header.getSrcAddr().getHostAddress(),tcpHeader.getSrcPort().value());
									if(
											tcpHeader.getDstPort().value()==listenPort){
										if(tcpHeader.getSyn()&&!tcpHeader.getAck()&&conn==null){
											conn=new TCPTun(capEnv,ipV4Header.getSrcAddr(),tcpHeader.getSrcPort().value());
											tcpManager.addConnection_Server(conn);
										}
										conn = tcpManager.getTcpConnection_Server(ipV4Header.getSrcAddr().getHostAddress(),tcpHeader.getSrcPort().value());
										if(conn!=null){
											conn.process_server(packet,head_eth,ipV4Header,tcpPacket,true);
										}
									}
								}
							}else if(packet_eth.getPayload() instanceof IllegalPacket){
								MLog.println("IllegalPacket!!!");
							}
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (IllegalRawDataException e) {
						e.printStackTrace();
					}
				}
			}

		};
		thread_process.start();
		
		Thread systemSleepScanThread=new Thread(){
			public void run(){
				long t=System.currentTimeMillis();
				while(true){
					if(System.currentTimeMillis()-t>5*1000){
						for(int i=0;i<10;i++){
							MLog.info("休眠恢复... "+(i+1));
							try {
								boolean success=initInterface();
								if(success){
									MLog.info("休眠恢复成功 "+(i+1));
									break;
								}
							} catch (Exception e1) {
								e1.printStackTrace();
							}
							
							try {
								Thread.sleep(5*1000);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
						
					}
					t=System.currentTimeMillis();
					try {
						Thread.sleep(1*1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		};
		systemSleepScanThread.start();
	}
	
	PromiscuousMode getMode(PcapNetworkInterface pi){
		PromiscuousMode mode=null;
		String string=(pi.getDescription()+":"+pi.getName()).toLowerCase();
		if(string.contains("wireless")){
			mode=PromiscuousMode.NONPROMISCUOUS;
		}else {
			mode=PromiscuousMode.PROMISCUOUS;
		}
		return mode;
	}
	
	boolean initInterface() throws Exception{
		boolean success=false;
		detectInterface();
		List<PcapNetworkInterface> allDevs = Pcaps.findAllDevs();
		MLog.println("Network Interface List: ");
		for(PcapNetworkInterface pi:allDevs){
			String desString="";
			if(pi.getDescription()!=null){
				desString=pi.getDescription();
			}
			MLog.info("  "+desString+"   "+pi.getName());
			if(pi.getName().equals(selectedInterfaceName)
					&&desString.equals(selectedInterfaceDes)){
				nif=pi;
				//break;
			}
		}
		if(nif!=null){
			String desString="";
			if(nif.getDescription()!=null){
				desString=nif.getDescription();
			}
			success=true;
			MLog.info("Selected Network Interface:\n"+"  "+desString+"   "+nif.getName());
			if(fwSuccess){
				tcpEnable=true;
			}
		}else {
			tcpEnable=false;
			MLog.info("Select Network Interface failed,can't use TCP protocal!\n");
		}
		if(tcpEnable){
			sendHandle = nif.openLive(SNAPLEN,getMode(nif), READ_TIMEOUT);
			final PcapHandle handle= nif.openLive(SNAPLEN, getMode(nif), READ_TIMEOUT);

			final PacketListener listener= new PacketListener() {
				@Override
				public void gotPacket(Packet packet) {

					try {
						if(packet instanceof EthernetPacket){
							packetList.add(packet);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}

				}
			};

			Thread thread=new Thread(){

				public void run(){
					try {
						handle.loop(COUNT, listener);
						PcapStat ps = handle.getStats();
						handle.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

			};
			thread.start();
		}
		
		if(!client){
			MLog.info("FinalSpeed server start success.");
		}
		return success;
	
	}

	void detectInterface() {
		List<PcapNetworkInterface> allDevs = null;
		Map<PcapNetworkInterface, PcapHandle> handleTable=new HashMap<PcapNetworkInterface, PcapHandle>();
		try {
			allDevs = Pcaps.findAllDevs();
		} catch (PcapNativeException e1) {
			e1.printStackTrace();
			return;
		}
		for(final PcapNetworkInterface pi:allDevs){
			try {
				final PcapHandle handle = pi.openLive(SNAPLEN, getMode(pi), READ_TIMEOUT);
				handleTable.put(pi, handle);
				final PacketListener listener= new PacketListener() {
					@Override
					public void gotPacket(Packet packet) {

						try {
							if(packet instanceof EthernetPacket){
								EthernetPacket packet_eth=(EthernetPacket) packet;
								EthernetHeader head_eth=packet_eth.getHeader();
								
								if(head_eth.getType().value()==0xffff8864){
									ppp=true;
									PacketUtils.ppp=ppp;
								}
								
								IpV4Packet ipV4Packet=null;
								IpV4Header ipV4Header=null;
								
								if(ppp){
									ipV4Packet=getIpV4Packet_pppoe(packet_eth);
								}else {
									if(packet_eth.getPayload() instanceof IpV4Packet){
										ipV4Packet=(IpV4Packet) packet_eth.getPayload();
									}
								}
								if(ipV4Packet!=null){
									ipV4Header=ipV4Packet.getHeader();
									
									if(ipV4Header.getSrcAddr().getHostAddress().equals(testIp_tcp)){
										local_mac=head_eth.getDstAddr();
										gateway_mac=head_eth.getSrcAddr();
										local_ipv4=ipV4Header.getDstAddr();
										selectedInterfaceName=pi.getName();
										if(pi.getDescription()!=null){
											selectedInterfaceDes=pi.getDescription();
										}
										//MLog.println("local_mac_tcp1 "+gateway_mac+" gateway_mac "+gateway_mac+" local_ipv4 "+local_ipv4);
									}
									if(ipV4Header.getDstAddr().getHostAddress().equals(testIp_tcp)){
										local_mac=head_eth.getSrcAddr();
										gateway_mac=head_eth.getDstAddr();
										local_ipv4=ipV4Header.getSrcAddr();
										selectedInterfaceName=pi.getName();
										if(pi.getDescription()!=null){
											selectedInterfaceDes=pi.getDescription();
										}
										//MLog.println("local_mac_tcp2 local_mac "+local_mac+" gateway_mac "+gateway_mac+" local_ipv4 "+local_ipv4);
									}
									//udp
									if(ipV4Header.getDstAddr().getHostAddress().equals(testIp_udp)){
										local_mac=head_eth.getSrcAddr();
										gateway_mac=head_eth.getDstAddr();
										local_ipv4=ipV4Header.getSrcAddr();
										selectedInterfaceName=pi.getName();
										if(pi.getDescription()!=null){
											selectedInterfaceDes=pi.getDescription();
										}
										//MLog.println("local_mac_udp "+gateway_mac+" gateway_mac"+gateway_mac+" local_ipv4 "+local_ipv4);
									}
								
								}
							}
						} catch (Exception e) {
							e.printStackTrace();
						}

					}
				};

				Thread thread=new Thread(){

					public void run(){
						try {
							handle.loop(COUNT, listener);
							PcapStat ps = handle.getStats();
							handle.close();
						} catch (Exception e) {
							//e.printStackTrace();
						}
					}

				};
				thread.start();
			} catch (PcapNativeException e1) {
				
			}
			
		}
		
		//detectMac_udp();
		try {
			detectMac_tcp();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	
	
		Iterator<PcapNetworkInterface> it=handleTable.keySet().iterator();
		while(it.hasNext()){
			PcapNetworkInterface pi=it.next();
			PcapHandle handle=handleTable.get(pi);
			try {
				handle.breakLoop();
			} catch (NotOpenException e) {
				e.printStackTrace();
			}
			//handle.close();//linux下会阻塞
		}
	}
	
	IpV4Packet getIpV4Packet_pppoe(EthernetPacket packet_eth) throws IllegalRawDataException{
		IpV4Packet ipV4Packet=null;
		byte[] pppData=packet_eth.getPayload().getRawData();
		if(pppData.length>8&&pppData[8]==0x45){
			byte[] b2=new byte[2];
			System.arraycopy(pppData, 4, b2, 0, 2);
			short len=(short) ByteShortConvert.toShort(b2, 0);
			int ipLength=toUnsigned(len)-2;
			byte[] ipData=new byte[ipLength];
			//设置ppp参数
			PacketUtils.pppHead_static[2]=pppData[2];
			PacketUtils.pppHead_static[3]=pppData[3];
			if(ipLength==(pppData.length-8)){
				System.arraycopy(pppData, 8, ipData, 0, ipLength);
				ipV4Packet=IpV4Packet.newPacket(ipData, 0, ipData.length);
			}else {
				MLog.println("长度不符!");
			}
		}
		return ipV4Packet;
	}
	
	
	
	public static String printHexString(byte[] b) {
		StringBuilder sb=new StringBuilder();
        for (int i = 0; i < b.length; i++)
        {
            String hex = Integer.toHexString(b[i] & 0xFF);
            hex=  hex.replaceAll(":", " ");
            if (hex.length() == 1)
            {
                hex = '0' + hex;
            }
            sb.append(hex + " ");
        }
        return sb.toString();
    }
	
	public void createTcpTun_Client(String dstAddress,short dstPort) throws Exception{
		Inet4Address serverAddress=(Inet4Address) Inet4Address.getByName(dstAddress);
		TCPTun conn=new TCPTun(this,serverAddress,dstPort,local_mac,gateway_mac);
		tcpManager.addConnection_Client(conn);
		boolean success=false;
		for(int i=0;i<6;i++){
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if(conn.preDataReady){
				success=true;
				break;
			}
		}
		if(success){
			tcpManager.setDefaultTcpTun(conn);
		}else {
			tcpManager.removeTun(conn);
			tcpManager.setDefaultTcpTun(null);
			throw new Exception("创建隧道失败!");
		}
	}
	
	private void detectMac_tcp() throws UnknownHostException{
		InetAddress address=InetAddress.getByName("www.bing.com");
		final int por=80;
		testIp_tcp=address.getHostAddress();
		for(int i=0;i<5;i++){
			try {
				Route.es.execute(new Runnable() {
					
					@Override
					public void run() {
						try {
							Socket socket=new Socket(testIp_tcp,por);
							socket.close();
						} catch (UnknownHostException e) {
							e.printStackTrace();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				});
				Thread.sleep(500);
				if(local_mac!=null){
					break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				try {
					Thread.sleep(1);
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
			}
		}
	}

	private void detectMac_udp(){
		for(int i=0;i<10;i++){
			try {
				DatagramSocket ds=new DatagramSocket();
				DatagramPacket dp=new DatagramPacket(new byte[1000], 1000);
				dp.setAddress(InetAddress.getByName(testIp_udp));
				dp.setPort(5555);
				ds.send(dp);
				ds.close();
				Thread.sleep(500);
				if(local_mac!=null){
					break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				try {
					Thread.sleep(1);
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
			}
		}

	}

	public short getListenPort() {
		return listenPort;
	}

	public void setListenPort(short listenPort) {
		this.listenPort = listenPort;
		if(!client){
			MLog.info("Listen tcp port: "+toUnsigned(listenPort));
		}
	}
	
	public static int toUnsigned(short s) {  
	    return s & 0x0FFFF;  
	}
	
}
