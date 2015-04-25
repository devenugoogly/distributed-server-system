/*
 * copyright 2015, gash
 * 
 * Gash licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package poke.server.queue;

import io.netty.channel.Channel;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import poke.comm.Image.Header;
import poke.comm.Image.PayLoad;
import poke.comm.Image.Ping;
import poke.comm.Image.Request;
import poke.server.managers.ConnectionManager;
import poke.server.managers.ElectionManager;
import poke.server.resources.database_connectivity;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;

public class InboundAppWorker extends Thread {
	protected static Logger logger = LoggerFactory.getLogger("server");

	int workerId;
	PerChannelQueue sq;
	boolean forever = true;
	int imageId = 0;
	database_connectivity db;
	HashMap<Integer, SortedMap<Integer,ByteString>> imageMap;
	SortedMap<Integer,ByteString> chunkMap;
	
	public InboundAppWorker(ThreadGroup tgrp, int workerId, PerChannelQueue sq) {
		
		super(tgrp, "inbound-" + workerId);
		this.workerId = workerId;
		this.sq = sq;
		db = new database_connectivity();
		imageMap = new HashMap<Integer, SortedMap<Integer,ByteString>>();
		chunkMap = new TreeMap<Integer, ByteString>();
		
		if (sq.inbound == null)
			throw new RuntimeException("connection worker detected null inbound queue");
	}

	@Override
	public void run() {
		Channel conn = sq.getChannel();
		if (conn == null || !conn.isOpen()) {
			logger.error("connection missing, no inbound communication");
			return;
		}

		while (true) {
			if (!forever && sq.inbound.size() == 0)
				break;

			try {
				// block until a message is enqueued
				GeneratedMessage msg = sq.inbound.take();
				System.out.println("Image received #####");
				// process request and enqueue response
				if (msg instanceof Request) {
					Request req = ((Request) msg);
					
					//Message to be modified
					Integer leaderNode = ElectionManager.getInstance().whoIsTheLeader();
					Integer nodeId = ElectionManager.getInstance().getNodeId();

					if(req.getPayload().hasChunkId())
						System.out.println("Chunk Received "+req.getPayload().getChunkId()+" "+req.getPayload().getTotalChunks());
					
					if(req.getPing().getIsPing())
					{	
						return;
					}	

					if(leaderNode != null && leaderNode == nodeId){
						Request.Builder newReq = Request.newBuilder();
						Header.Builder newHeader = Header.newBuilder();
						PayLoad.Builder newPayload = PayLoad.newBuilder();
						Ping.Builder newPing = Ping.newBuilder();
						
						newHeader.setClientId(req.getHeader().getClientId());
						newHeader.setClusterId(req.getHeader().getClusterId());
						newHeader.setCaption(req.getHeader().getCaption());
						newHeader.setIsClient(false);
						
						newPing.setIsPing(req.getPing().getIsPing());
						
						newPayload.setData(req.getPayload().getData());
						
						newReq.setHeader(newHeader);
						newReq.setPing(newPing);
						newReq.setPayload(newPayload);
						
						Request request = newReq.build();
						
						ConnectionManager.broadcast(request);
						System.out.println("---------------- "+newHeader.getIsClient()+"Sending to all clusters");
						
						
						if(req.getHeader().getClusterId() != 9)
						ConnectionManager.interClusterBroadcast(request);
						System.out.println("--------------"+newHeader.getIsClient()+"Sent to all clusters");
						ConnectionManager.broadcastToClient(request);
						
						createImage(req);
						
						
					}else if(leaderNode != nodeId ){
						//Build new Request
						if(req.getHeader().getIsClient() == true){
							Request.Builder newReq = Request.newBuilder();
							Header.Builder newHeader = Header.newBuilder();
							PayLoad.Builder newPayload = PayLoad.newBuilder();
							Ping.Builder newPing = Ping.newBuilder();
							
							newHeader.setClientId(req.getHeader().getClientId());
							newHeader.setClusterId(req.getHeader().getClusterId());
							newHeader.setCaption(req.getHeader().getCaption());
							newHeader.setIsClient(false);
							
							newPing.setIsPing(false);
							
							newPayload.setData(req.getPayload().getData());
							
							
							newReq.setHeader(newHeader);
							newReq.setPing(newPing);
							newReq.setPayload(newPayload);
							System.out.println("Sending to leade_______________________r");
							ConnectionManager.unicast(newReq.build());
						}else{
							createImage(req);
						}
					}
					
					// HEY! if you find yourself here and are tempted to add
					// code to process state or requests then you are in the
					// WRONG place! This is a general routing class, all
					// request specific actions should take place in the
					// resource!

					// handle it locally - we create a new resource per
					// request. This helps in thread isolation however, it
					// creates creation burdens on the server. If
					// we use a pool instead, we can gain some relief.

//					Resource rsc = ResourceFactory.getInstance().resourceInstance(req.getHeader());
//
//					Request reply = null;
//					if (rsc == null) {
//						logger.error("failed to obtain resource for " + req);
//						reply = ResourceUtfil
//								.buildError(req.getHeader(), PokeStatus.NORESOURCE, "Request not processed");
//					} else {
//						// message communication can be two-way or one-way.
//						// One-way communication will not produce a response
//						// (reply).
//						reply = rsc.process(req);
//					}
//
//					if (reply != null)
//						sq.enqueueResponse(reply, null);
				}

			} catch (InterruptedException ie) {
				break;
			} catch (Exception e) {
				logger.error("Unexpected processing failure", e);
				break;
			}
		}

		if (!forever) {
			logger.info("connection queue closing");
		}
	}
	
	
	public boolean createImage(Request req){
		boolean rtn = false;
		BufferedImage img;
		
		chunkMap.put(req.getPayload().getChunkId(),req.getPayload().getData());
		imageMap.put(req.getPayload().getImgId(), chunkMap);
		
//		System.out.println(imageMap.get(req.getPayload().getImgId()).size());
		System.out.println("@@@@@@@@@@@@@");
		System.out.println(chunkMap.keySet());
		
//		System.out.println("Chunk Received "+req.getPayload().getChunkId()+" "+req.getPayload().getTotalChunks());
		int chunkNo = imageMap.get(req.getPayload().getImgId()).entrySet().size();
//		System.out.println("Total Map Chunk "+chunkNo);
		if( chunkNo== req.getPayload().getTotalChunks()){
//		System.out.println("Image received");
			ByteArrayOutputStream stream = new ByteArrayOutputStream( );
		
		Iterator<Entry<Integer, ByteString>> it = imageMap.get(req.getPayload().getImgId()).entrySet().iterator();
		try {
		while(it.hasNext()){
			Map.Entry pair = (Map.Entry)it.next();
			ByteString bst = (ByteString) pair.getValue();
			stream.write(bst.toByteArray());
		}
//				img = ImageIO.read(new ByteArrayInputStream(req.getPayload().getData().toByteArray()));
		ByteArrayInputStream stream1 = new ByteArrayInputStream(stream.toByteArray());
		img = ImageIO.read(stream1);
				try {
					ImageIO.write(img, "png", new File("../../images/"+imageId+".png"));
					String query = "insert into CMPE_275.Data values ("+ElectionManager.getInstance().getNodeId()+","+ElectionManager.getInstance().getTermId()+
							","+imageId+", '../../images/"+imageId+".png','"+req.getHeader().getCaption()+"')";
					System.out.println(">>>>>>>Splitting>>>>>>");
					splitImage(img);
					db.execute_query(query);
				}
				catch (SQLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				imageId++;
				rtn = true;
			}
			catch (IOException e) {
				e.printStackTrace();
				rtn = false;
			}
		
		}
		return rtn;
	}
	
	public byte[][] splitImage(BufferedImage image) throws IOException{
		int rows = 0; //You should decide the values for rows and cols variables  
        int cols = 0;  
		
        if(image.getWidth() > 500){
			rows = (image.getWidth()/500);
		}
		
        if(image.getHeight() > 500){
			cols = (image.getHeight()/500);
		}
		
        int chunks = rows * cols;  
  
        int chunkWidth = image.getWidth() / cols; // determines the chunk width and height  
        int chunkHeight = image.getHeight() / rows;  
        int count = 0;  
        BufferedImage imgs[] = new BufferedImage[chunks]; //Image array to hold image chunks  
        for (int x = 0; x < rows; x++) {  
            for (int y = 0; y < cols; y++) {  
                //Initialize the image array with image chunks  
                imgs[count] = new BufferedImage(chunkWidth, chunkHeight, image.getType());  
  
                // draws the image chunk  
                Graphics2D gr = imgs[count++].createGraphics();  
                gr.drawImage(image, 0, 0, chunkWidth, chunkHeight, chunkWidth * y, chunkHeight * x, chunkWidth * y + chunkWidth, chunkHeight * x + chunkHeight, null);  
                gr.dispose();  
            }  
        }  
        System.out.println("Splitting done");  
  
        //writing mini images into image files  
        byte[][] imageChunks = new byte[imgs.length][];
        for (int i = 0; i < imgs.length; i++) {  
            try {
            	ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
				ImageIO.write(imgs[i], "png", new File("../../images/img" + i + ".png"));
//            	ImageIO.write(imgs[i], "png", byteArrayOS);
            	byteArrayOS.flush();
            	byte[] byteArray = byteArrayOS.toByteArray();
            	imageChunks[i] = byteArray;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}  
        }
        System.out.println("Mini images created");
        return imageChunks;
    }
}