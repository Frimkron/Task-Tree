/*
Copyright (c) 2010 Mark Frimston

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
*/

package uk.co.markfrimston.tasktree.test;

import java.net.*;
import java.io.*;
import java.util.*;
import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.impl.client.*;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;

public class MockServer extends Thread
{
	public static class Data
	{
		public String initialLine = "";
		public Map<String,String> headers = new HashMap<String,String>();
		public byte[] body;
	}
	
	public static interface RequestHandler
	{
		public void handleRequest(Data request, Data response);
	}
	
	int port;
	boolean stop = false;
	ServerSocket serverSocket;
	Socket clientSocket;
	RequestHandler requestHandler;
	
	public MockServer(int port, RequestHandler requestHandler)
	{
		this.port = port;
		this.requestHandler = requestHandler;
	}
	
	public void setRequestHandler(RequestHandler requestHandler)
	{
		this.requestHandler = requestHandler;
	}
	
	public void run()
	{
		try
		{
			serverSocket = new ServerSocket(port);
			
			while(!stop)
			{
				try
				{
					System.out.println("Server waiting for connection");
					clientSocket = serverSocket.accept();
				
					Data requestData = new Data();
				
					BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
					String initialLine = "";
					Map<String,String> headers = new HashMap<String,String>();
					int part = 0;
					String line = null;
					long contentRead = 0;	
					reading:
					while((line=in.readLine()) != null)
					{
						System.out.println("Request line: "+line);
						switch(part)
						{
							case 0: //initial line
								initialLine = line;
								part = 1;
								break;
							case 1: //headers
								if(line.equals("")){
									if(headers.containsKey("Content-Length") 
											&& Long.parseLong(headers.get("Content-Length").trim())==0){
										part = 3;
									}else{
										part = 2;
									}
									break reading;
								}else{
									headers.put(line.split(":")[0], line.split(":")[1]);
								}			
								break;
						}				
					}
				
					OutputStreamWriter out = new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8");
					List<String> outLines = new ArrayList<String>();
				
					//body
					byte[] bodyBytes = {};
					if(part==2)
					{		
						// if client wants a 100-continue, issue it now before reading request body
						if(headers.containsKey("Expect")
								&& headers.get("Expect").trim().equalsIgnoreCase("100-Continue"))
						{
							// continue response
							outLines.add("HTTP/1.1 100 Continue");
							outLines.add("");	
							
							for(String outLine : outLines)
							{
								System.out.println("Response line: "+outLine);
								out.write(outLine+"\r\n");
							}
							outLines.clear();
							System.out.println("Flushing output");
							out.flush();
						}
						
						long contentLength = -1;
						if(headers.containsKey("Content-Length")){
							contentLength = Long.parseLong(headers.get("Content-Length").trim());
						}
						InputStream rawIn = clientSocket.getInputStream();
						ByteArrayOutputStream baos = new ByteArrayOutputStream();
						byte[] buffer = new byte[1024];
						while(true)
						{					
							if(contentRead >= contentLength){
								break;
							}
							int bytesRead = rawIn.read(buffer);
							if(bytesRead == -1){
								break;
							}
							baos.write(buffer,0,bytesRead);
							contentRead += bytesRead;
						}
						bodyBytes = baos.toByteArray();
						String bodyString = new String(bodyBytes);
						System.out.println("Request body: "+bodyString);
					}
					
					requestData.initialLine = initialLine;
					requestData.headers = headers;
					requestData.body = bodyBytes;
					
					Data responseData = new Data();
					responseData.initialLine = "HTTP/1.1 200 OK";
					responseData.headers.put("Connection", "close");
					requestHandler.handleRequest(requestData, responseData);
				
					System.out.println("Server responding");		
					outLines.add(responseData.initialLine);					
					if(responseData.headers!=null)
					{
						for(String header : responseData.headers.keySet()){
							outLines.add(header+": "+responseData.headers.get(header));
						}
					}
					outLines.add("");
					for(String outLine : outLines)
					{
						System.out.println("Response line: "+outLine);
						out.write(outLine+"\r\n");
					}
					out.flush();
					if(responseData.body!=null && responseData.body.length>0)
					{
						System.out.println("Response body: "+new String(responseData.body));
						clientSocket.getOutputStream().write(responseData.body);
						clientSocket.getOutputStream().write(new byte[]{'\r','\n'});
					}								
					
					outLines.clear();
					System.out.println("Flushing output");
					clientSocket.getOutputStream().flush();
				}
				finally
				{
					try{
						clientSocket.close();
					}catch(Exception e){}
				}
			}
								
		}
		catch(Exception e)
		{
			if(!stop){
				e.printStackTrace();
			}
		}
		finally
		{
			try{
				serverSocket.close();
			}catch(Exception e){}			
		}
	}
	
	public void requestStop(long timeout) throws Exception
	{
		stop = true;
		try{	
			serverSocket.close();
		}catch(Exception e){}
		try{
			clientSocket.close();
		}catch(Exception e){}
		
		if(this.isAlive())
		{
			try{
				this.join(timeout);
			}catch(InterruptedException e){}
		}
		
	}
	
	public static void main(String[] args) throws Exception
	{
		MockServer mock = new MockServer(4444, new RequestHandler(){
			public void handleRequest(Data request, Data response)			
			{
				try
				{
					response.headers.put("Content-Type", "text/xml");
					response.body = 
						("<test>\n"+
						"	<msg>hello</msg>\n"+
						"</test>\n").getBytes("Utf-8");
				}
				catch(UnsupportedEncodingException e){}
			}
		});			
		mock.start();
		
		HttpClient client = new DefaultHttpClient();
		HttpPost post = new HttpPost("http://localhost:4444");
		post.setEntity(new StringEntity(
				"<test>\n"
				+"	<foo>\n"
				+"		<bar />\n"
				+"		<bar />\n"
				+"	</foor>\n"
				+"</test>\n",
				"Utf-8"));
		HttpResponse response = client.execute(post);
		System.out.println("Status: "+response.getStatusLine().getStatusCode());
		System.out.println("Headers: ");
		for(Header header : response.getAllHeaders())
		{
			System.out.println("\t"+header.getName()+": "+header.getValue());
		}
		System.out.println("Body: ");
		response.getEntity().writeTo(System.out);
		
		mock.requestStop(1000);
	}
}
