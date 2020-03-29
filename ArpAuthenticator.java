package net.floodlightcontroller.sdn_arp_spoof_detection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.ArpOpcode;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv4AddressWithMask;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.Masked;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.DHCP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.restserver.IRestApiService;
//import net.floodlightcontroller.sdn_arp_spoof_detection.web.*;

import java.util.HashMap;

import net.floodlightcontroller.dhcpserver.*;
public class ArpAuthenticator implements IFloodlightModule, IOFMessageListener ,IOFSwitchListener{
	protected IOFSwitchService switchService;
	protected static Logger log = LoggerFactory.getLogger(ArpAuthenticator.class);
	protected IRestApiService restApiService;
	protected IFloodlightProviderService floodlightProviderService;
	protected PortIPTable portIPMap;
	protected MacPortTable macPortTable;
	protected ARPDHCP dhcp;


	@Override
	public String getName() {
		return "ArpAuthenticator";
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	//This module will get packet before forwarding module
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return true;
	}

	private void removePortFlow(IOFSwitch sw,OFPort port, VlanVid vid){
		OFFactory factory = sw.getOFFactory();
		Match match = factory.buildMatch().setExact(MatchField.IN_PORT,port).setExact(MatchField.VLAN_VID,OFVlanVidMatch.ofVlanVid(vid)).setExact(MatchField.ETH_TYPE,EthType.ARP).setMasked(MatchField.ARP_SPA,IPv4AddressWithMask.of("0.0.0.0")).build();
		OFFlowDelete flowDel = factory.buildFlowDelete().setMatch(match).build();
		sw.write(flowDel);
	}

	// handles both ARP and DHCP
	protected Command handlePacketInMessage(IOFSwitch sw,OFPacketIn pi,FloodlightContext cntx){
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort()
				: pi.getMatch().get(MatchField.IN_PORT));
		VlanVid vid = VlanVid.ofVlan(eth.getVlanID());

		if(eth.getEtherType().equals(EthType.ARP)){
			ARP arp = (ARP) eth.getPayload();
			if(arp.getTargetProtocolAddress().equals(arp.getSenderProtocolAddress())) return Command.CONTINUE;
			OFFactory factory = sw.getOFFactory();
			String str ;
			if(arp.getOpCode().equals(ArpOpcode.REPLY)) str = "REPLY";
			else str = "REQUEST";
			log.info("GOT a {} from {}",str,sw);
			log.info("FROM {} TO {}",arp.getSenderProtocolAddress(),arp.getTargetProtocolAddress());
			Match match;
			if(vid.equals(VlanVid.ZERO)){
				match = factory.buildMatch().setExact(MatchField.ETH_TYPE,EthType.ARP).setExact(MatchField.VLAN_VID,OFVlanVidMatch.UNTAGGED).setExact(MatchField.ARP_TPA,arp.getSenderProtocolAddress()).build();
			}else{
				match = factory.buildMatch().setExact(MatchField.ETH_TYPE,EthType.ARP).setExact(MatchField.VLAN_VID,OFVlanVidMatch.ofVlanVid(vid)).setExact(MatchField.ARP_TPA,arp.getSenderProtocolAddress()).build();
			}
			ArrayList<OFAction> list = new ArrayList<>();
			list.add(factory.actions().buildOutput().setPort(inPort).build());
			OFFlowAdd flowAdd = factory.buildFlowAdd().setTableId(TableId.of(1)).setHardTimeout(0).setIdleTimeout(10).setPriority(30).setActions(list).setMatch(match).build();
			sw.write(flowAdd);
		} 

		/* if we get a dhcp request from a port it means a 
		 * new device is connected to that port.
		 * So, remove any entry from data-structure and 
		 * flow rules from switch for that port(if any)
		 *
		 * if we get a dhcp ack (which can be sent by
		 * any dhcpserver machine or VM as it is a packet
		 * in message) so handle it by registration of
		 * IP address
		 */
		else if(DHCPServerUtils.isDHCPPacketIn(eth)){
			DHCP payload = DHCPServerUtils.getDHCPayload(eth);
			if(DHCPServerUtils.getMessageType(payload).equals(IDHCPService.MessageType.REQUEST)){
				if(portIPMap.vlanExists(sw.getId(),inPort,VlanVid.ofVlan(eth.getVlanID()))){
					removePortFlow(sw,inPort,vid);
					MacAddress tmpMac = portIPMap.getMacForVlan(sw.getId(),inPort,vid);
					portIPMap.remove(sw.getId(),inPort,VlanVid.ofVlan(eth.getVlanID()));
					macPortTable.removeVid(tmpMac,vid);
				}
				if(!macPortTable.vidExists(eth.getSourceMACAddress(),vid)){
					log.info("GOT REQUEST: {}",eth.getSourceMACAddress());
					macPortTable.addEntry(eth.getSourceMACAddress(),vid,sw.getId(),inPort);
				}
			}else if(DHCPServerUtils.getMessageType(payload).equals(IDHCPService.MessageType.ACK)){
				log.info("GOT ACK: {}",eth.getDestinationMACAddress());
				handleDHCPACK(eth,payload);
			}
		}
		return Command.CONTINUE;
	}



	/* registers the new IP address in switchMap.
	 * It gets the required switch and port for 
	 * registration using macMap.
	 * macMap entry itself was added while handling 
	 * DHCPRequest previously.
	 */
	private void handleDHCPACK(Ethernet eth,DHCP payload){
		VlanVid vid = VlanVid.ofVlan(eth.getVlanID());
		SwitchPortPair pair = macPortTable.getPortForMac(eth.getDestinationMACAddress(),vid);
		portIPMap.addEntry(pair.getSwitch(),pair.getPort(),new VlanIPPair(vid,payload.getYourIPAddress(),eth.getDestinationMACAddress()));
		IOFSwitch sw = switchService.getSwitch(pair.getSwitch());
		if(sw!=null){
			installProactiveRules(sw,pair.getPort(),vid,payload.getYourIPAddress());
		}else{
			switchRemoved(pair.getSwitch());
		}
	}

	private void installProactiveRules(IOFSwitch sw, OFPort port,VlanVid vid, IPv4Address addr){
		OFFactory factory = sw.getOFFactory();
		Match match = factory.buildMatch().setExact(MatchField.IN_PORT,port).setMasked(MatchField.VLAN_VID,Masked.of(OFVlanVidMatch.FULL_MASK,OFVlanVidMatch.FULL_MASK)).setExact(MatchField.ETH_TYPE,EthType.ARP).setMasked(MatchField.ARP_SPA,IPv4AddressWithMask.of("0.0.0.0/0")).build();
		ArrayList<OFAction> actionList = new ArrayList<>();
		OFFlowAdd flowAdd = factory.buildFlowAdd().setMatch(match).setHardTimeout(0).setIdleTimeout(0).setActions(actionList).setPriority(10).build();
		sw.write(flowAdd);
		ArrayList<OFInstruction> insSet  = new ArrayList<>();
		insSet.add(factory.instructions().buildGotoTable().setTableId(TableId.of(1)).build());
		if(vid.equals(VlanVid.ZERO)){
			log.info("FOUND WITHOUT ANY VLAN");
			match = factory.buildMatch().setExact(MatchField.IN_PORT,port).setExact(MatchField.VLAN_VID,OFVlanVidMatch.UNTAGGED).setExact(MatchField.ETH_TYPE,EthType.ARP).setExact(MatchField.ARP_SPA,addr).build();
		}else{
			match = factory.buildMatch().setExact(MatchField.IN_PORT,port).setExact(MatchField.VLAN_VID,OFVlanVidMatch.ofVlanVid(vid)).setExact(MatchField.ETH_TYPE,EthType.ARP).setExact(MatchField.ARP_SPA,addr).build();
		}
		flowAdd = factory.buildFlowAdd().setMatch(match).setHardTimeout(0).setIdleTimeout(0).setInstructions(insSet).setPriority(20).build();
		sw.write(flowAdd);
	}


	/* handles DHCPACK by controller
	 * as if controller generates ack 
	 * it will be a packet-out message
	 */
	private Command handlePacketOutMessage(IOFSwitch sw,OFPacketOut pi,FloodlightContext cntx){
		Ethernet eth = new Ethernet();
		eth = (Ethernet) eth.deserialize(pi.getData(),0,pi.getData().length);
		if(DHCPServerUtils.isDHCPPacketIn(eth)){
			DHCP payload = DHCPServerUtils.getDHCPayload(eth);
			if(DHCPServerUtils.getMessageType(payload).equals(IDHCPService.MessageType.ACK)){
				handleDHCPACK(eth,payload);
			}
		}
		return Command.CONTINUE;

	}


	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		if(!portIPMap.switchExists(sw.getId())) switchAdded(sw.getId());
		if(msg.getType().equals(OFType.PACKET_IN)) return handlePacketInMessage(sw,(OFPacketIn) msg,cntx);
		else if(msg.getType().equals(OFType.PACKET_OUT)) return handlePacketOutMessage(sw,(OFPacketOut)msg,cntx);
		return Command.CONTINUE;
	}



	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l  = new ArrayList<>();
		//l.add(IArpAuthenticatorService.class);
		return l;
	}


	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,IFloodlightService> m  = new HashMap<>();
		//m.put(IArpAuthenticatorService.class,this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IRestApiService.class);
		l.add(IFloodlightProviderService.class);
		l.add(IOFSwitchService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		portIPMap = new PortIPTable();
		macPortTable = new MacPortTable();
		dhcp = new ARPDHCP(context);
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProviderService.addOFMessageListener(OFType.PACKET_OUT, this);
		//restApiService.addRestletRoutable(new ArpAuthenticatorWebRoutable());
		switchService.addOFSwitchListener(this);
	}

	/*
	@Override
	public HashMap<DatapathId, HashMap<OFPort, IPMacPair>> getArpMap() {
		return switchMap;
	}*/

	@Override
	public void switchAdded(DatapathId switchId) {
		if(!portIPMap.switchExists(switchId)){
			IOFSwitch sw = switchService.getSwitch(switchId);
			OFFactory factory = sw.getOFFactory();
			Match tableMatch = factory.buildMatch().setExact(MatchField.ETH_TYPE,EthType.ARP).build();
			ArrayList<OFInstruction> tableList = new ArrayList<>();
			tableList.add(factory.instructions().buildGotoTable().setTableId(TableId.of(1)).build());
			OFFlowAdd tableAdd = factory.buildFlowAdd().setMatch(tableMatch).setInstructions(tableList).setIdleTimeout(0).setHardTimeout(0).setPriority(5).build();
			sw.write(tableAdd);
			Match arpMatch = factory.buildMatch().setExact(MatchField.ETH_TYPE,EthType.ARP).build();
			ArrayList<OFAction> list = new ArrayList<>();
			list.add(factory.actions().buildOutput().setPort(OFPort.CONTROLLER).build());
			OFFlowAdd flowAdd = factory.buildFlowAdd().setMatch(arpMatch).setIdleTimeout(0).setHardTimeout(0).setPriority(0).setActions(list).setTableId(TableId.of(1)).build();
			sw.write(flowAdd);
		}
	}

	@Override
	public void switchRemoved(DatapathId switchId) {
		portIPMap.remove(switchId);
		macPortTable.removeSwitch(switchId);
	}

	@Override
	public void switchActivated(DatapathId switchId) {

	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {

	}

	@Override
	public void switchChanged(DatapathId switchId) {

	}

	@Override
	public void switchDeactivated(DatapathId switchId) {

	}

}
