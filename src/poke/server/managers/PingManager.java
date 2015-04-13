package poke.server.managers;

import io.netty.channel.Channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import poke.comm.Image.Header;
import poke.comm.Image.PayLoad;
import poke.comm.Image.Ping;
import poke.comm.Image.Request;
import poke.server.comm.monitor.CommMonitor;
import poke.server.conf.ServerConf;



public class PingManager {

	protected static Logger logger = LoggerFactory.getLogger("pingmanager");
	protected static AtomicReference<PingManager> instance = new AtomicReference<PingManager>();
	private static HashMap<String, CommMonitor> commMap = new HashMap<String, CommMonitor>();
	Request.Builder req;
	
	public static PingManager initManager() {
		
		logger.info("inside PingMangerInit");
		File f= new File("../../resources/pingIPs");
		readFile(f);
		instance.compareAndSet(null, new PingManager());
		return instance.get();
	}
	
	
	public static PingManager getInstance() {
		// TODO Auto-generated method stub
		return instance.get();
	}
	
	//This sets up a connection with all the machines in the network and declares that he is the leader of this cluster.
	public void declareSupremacy()
	{
		Integer nodeId = ElectionManager.getInstance().getNodeId();
		Integer leaderId = ElectionManager.getInstance().whoIsTheLeader();
		if(nodeId == leaderId){
			for(CommMonitor val : commMap.values() ){
				if(!val.isConnected())
					val.connect();
				else
					val.sendMessage(req.build());
			}
			
		}
	}
	
	protected static void readFile(File f){
		
		// Open the file
		FileInputStream fstream;
		try {
			fstream = new FileInputStream(f);
		
		BufferedReader br = new BufferedReader(new InputStreamReader(fstream));
		String[] ip =null;

		String strLine;

		//Read File Line By Line
		
			while ((strLine = br.readLine()) != null)   {
			  // Print the content on the console
				 ip=strLine.split("\\s+");
				 CommMonitor monitor = new CommMonitor(1,ip[0],Integer.parseInt(ip[1]),1);
				 commMap.put(ip[0],monitor);
			}
			//Close the input stream
			 br.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		}
	
	private void createRequest(){
		ByteString bs = null;
		req = Request.newBuilder();
		Header.Builder newHeader = Header.newBuilder();
		PayLoad.Builder newPayload = PayLoad.newBuilder();
		Ping.Builder newPing = Ping.newBuilder();
		
		newHeader.setClientId(0);
		newHeader.setClusterId(4);
		newHeader.setCaption("");
		newHeader.setIsClient(false);
		
		newPing.setIsPing(true);
		
		newPayload.setData(bs);
		
		req.setHeader(newHeader);
		req.setPing(newPing);
		req.setPayload(newPayload);
	}

}
