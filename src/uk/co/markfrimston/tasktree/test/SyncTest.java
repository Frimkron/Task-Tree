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

import org.junit.*;
import java.io.*;
import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.impl.client.*;
import org.apache.http.client.methods.*;
import uk.co.markfrimston.tasktree.*;
import javax.swing.tree.*;

import static org.junit.Assert.*;

public class SyncTest
{
	protected static final String TEST_CONFIG_PATH = "./testconfig/";
	protected static final int TEST_PORT = 4444;
	protected static final String TEST_LOAD_URL = "http://localhost:"+TEST_PORT+"/load";
	protected static final String TEST_SAVE_URL = "http://localhost:"+TEST_PORT+"/save";
	protected static final MergeConfirmer YES_MC = new MergeConfirmer(){
		public boolean confirmMerge(){ return true; }
	};
	protected static final MergeConfirmer NO_MC = new MergeConfirmer(){
		public boolean confirmMerge(){ return false; }
	};
	
	protected static MockServer ms;
	protected TaskTree tt;
	protected boolean loadRequested;
	protected boolean saveRequested;
	protected boolean merged;
	
	protected void cleanUpFiles()
	{
		// clean up files
		File path = new File(TEST_CONFIG_PATH);
		if(path.exists())
		{
			for(File file : path.listFiles())
			{
				file.delete();
			}		
		}
		path.delete();
	}
	
	protected String getNodeLabel(int... nodeIndices)
	{
		DefaultMutableTreeNode currentNode = tt.getRoot();
		for(int nodeIndex : nodeIndices)
		{
			currentNode = (DefaultMutableTreeNode)currentNode.getChildAt(nodeIndex);
		}
		return (String)currentNode.getUserObject();
	}
	
	@BeforeClass
	public static void setUpSuite()
	{
		ms = new MockServer(TEST_PORT,null);
		ms.start();
	}
	
	@AfterClass
	public static void tearDownSuite() throws Exception
	{
		ms.requestStop(3000);
	}
	
	@Before
	public void setUp()
	{
		cleanUpFiles();
		tt = new TaskTree(TEST_CONFIG_PATH);
	}
	
	@After
	public void tearDown()
	{
		cleanUpFiles();
	}
	
	@Test(expected=Exception.class)
	public void testSyncNoUrl() throws Exception
	{
		/*
		 * Shouldn't be able to sync without urls defined
		 */
		tt.synchronise(YES_MC);
	}
	
	@Test
	public void testSyncWithYounger() throws Exception
	{
		/*
		 * If server data is younger, something has gone wrong
		 */
		tt.setUnsynchedChanges(true);
		tt.setLastSyncTime(1000L);
		tt.setLoadUrl(TEST_LOAD_URL);
		tt.setSaveUrl(TEST_SAVE_URL);
		tt.setMergeCommand("");
		
		loadRequested = false;
		ms.setRequestHandler(new MockServer.RequestHandler(){
			public void handleRequest(MockServer.Data request, MockServer.Data response)
			{
				try{
					loadRequested = true;
					response.headers.put("Content-Type", "text/xml");
					response.headers.put("Timestamp","500");
					response.body = (
							"<tasklist><tasks /></tasktree>"
					).getBytes("Utf-8");
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		});
		
		boolean thrown = false;
		try
		{
			tt.synchronise(NO_MC);
		}
		catch(Exception e)
		{
			thrown = true;
		}
		
		assertTrue(loadRequested);
		assertTrue(thrown);
	}
	
	@Test
	public void testSyncChangesWithEqual() throws Exception
	{
		/*
		 * If we're synching changes and server time is equal, something's gone
		 * wrong
		 */
		tt.setUnsynchedChanges(true);
		tt.setLastSyncTime(1000L);
		tt.setLoadUrl(TEST_LOAD_URL);
		tt.setSaveUrl(TEST_SAVE_URL);
		tt.setMergeCommand("");
		
		loadRequested = false;
		ms.setRequestHandler(new MockServer.RequestHandler(){
			public void handleRequest(MockServer.Data request, MockServer.Data response)
			{
				try{
					loadRequested = true;
					response.headers.put("Content-Type", "text/xml");
					response.headers.put("Timestamp", "1000");
					response.body = (
							"<tasklist><tasks /></tasklist>"
					).getBytes("Utf-8");
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		});						
		
		boolean thrown = false;
		try
		{
			tt.synchronise(NO_MC);
		}
		catch(Exception e)
		{
			thrown = true;
		}
		
		assertTrue(loadRequested);
		assertTrue(thrown);
	}
	
	@Test
	public void testSyncNoChangesWithEqual() throws Exception
	{
		/*
		 * If we have made no changes and server time is equal, there's nothing
		 * to update.
		 */
		tt.setUnsynchedChanges(false);
		tt.setLastSyncTime(1000L);
		tt.setLoadUrl(TEST_LOAD_URL);
		tt.setSaveUrl(TEST_SAVE_URL);
		tt.setMergeCommand("");
		
		/*
		 * |- foo
		 * |   '- bar
		 * '- weh
		 */
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		DefaultMutableTreeNode foo = new DefaultMutableTreeNode();
		foo.setUserObject("foo");
		DefaultMutableTreeNode bar = new DefaultMutableTreeNode();
		bar.setUserObject("bar");
		foo.add(bar);
		root.add(foo);
		DefaultMutableTreeNode weh = new DefaultMutableTreeNode();
		weh.setUserObject("weh");
		root.add(weh);
		
		tt.setRoot(root);
		tt.getTreeModel().setRoot(root);
		
		loadRequested = false;
		ms.setRequestHandler(new MockServer.RequestHandler(){
			public void handleRequest(MockServer.Data request, MockServer.Data response)
			{
				try{
					loadRequested = true;
					response.headers.put("Content-Type", "text/xml");
					response.headers.put("Timestamp","1000");
					response.body = (
							"<tasklist>\n"+
							"	<tasks>\n"+
							"		<task label=\"foo\">\n"+
							"			<task label=\"bar\" />\n"+
							"		</task>\n"+
							"		<task label=\"weh\"/>\n"+
							"	</tasks>\n"+
							"</tasklist>\n"
					).getBytes("Utf-8");
					
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		});
		
		tt.synchronise(NO_MC);
		
		assertTrue(loadRequested);
		
		// tree should remain the same
		assertEquals("foo", getNodeLabel(0));
		assertEquals("bar", getNodeLabel(0,0));
		assertEquals("weh", getNodeLabel(1));
	}
	
	
	
	@Test
	public void testSyncNoChangesWithOlder() throws Exception
	{
		/*
		 * If we sync with server but haven't made changes, should update tree
		 * with server's version
		 */
		tt.setUnsynchedChanges(false);
		tt.setLastSyncTime(1000L);
		tt.setLoadUrl(TEST_LOAD_URL);
		tt.setSaveUrl(TEST_SAVE_URL);
		tt.setMergeCommand("");
		
		final String data = 
			"<tasklist>\n"+
			"	<tasks>\n"+
			"		<task label=\"foo\">\n"+
			"			<task label=\"bar\" />\n"+
			"		</task>\n"+
			"		<task label=\"weh\"/>\n"+
			"	</tasks>\n"+
			"</tasklist>\n";
		
		loadRequested = false;
		saveRequested = false;
		
		ms.setRequestHandler(new MockServer.RequestHandler(){
			public void handleRequest(MockServer.Data request, MockServer.Data response)
			{
				try{
					if(request.initialLine.split(" +")[1].endsWith("load"))
					{
						loadRequested = true;
						response.headers.put("Timestamp", "2000");
						response.headers.put("Content-Type", "text/xml");
						response.body = data.getBytes("Utf-8");
					}
					else if(request.initialLine.split(" +")[1].endsWith("save"))
					{
						saveRequested = true;
						response.headers.put("Content-Type", "text/xml");
						response.body = data.getBytes("Utf-8");
					}
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		});				
				
		tt.synchronise(NO_MC);
			
		assertTrue(loadRequested);
		assertTrue(saveRequested);
		
		assertEquals("foo", getNodeLabel(0));
		assertEquals("weh", getNodeLabel(1));
		assertEquals("bar", getNodeLabel(0, 0));
	}
	
	@Test
	public void testSyncChangesWithOlder() throws Exception
	{
		/*
		 *	If we have made changes and the server has also been updated, then
		 *  a merge should be requested and the result saved back to the server.
		 */				
		tt.setUnsynchedChanges(true);
		tt.setLastSyncTime(1000L);
		tt.setLoadUrl(TEST_LOAD_URL);
		tt.setSaveUrl(TEST_SAVE_URL);
		tt.setMergeCommand("echo foobar");
		
		/*
		 * |- foo
		 * |   '- bar
		 * '- weh
		 */
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		DefaultMutableTreeNode foo = new DefaultMutableTreeNode();
		foo.setUserObject("foo");
		DefaultMutableTreeNode bar = new DefaultMutableTreeNode();
		bar.setUserObject("bar");
		foo.add(bar);
		root.add(foo);
		DefaultMutableTreeNode weh = new DefaultMutableTreeNode();
		weh.setUserObject("weh");
		root.add(weh);
		tt.setRoot(root);
		
		final String data1 = 
			"<tasklist>\n"+
			"	<tasks>\n"+
			"		<task label=\"foo\" />\n"+
			"	</tasks>\n"+
			"</tasklist>\n";
		
		final String data2 = 
			"<tasklist>\n"+
			"	<tasks>\n"+
			"		<task label=\"foo\">\n"+
			"			<task label=\"bar\" />\n"+
			"		</task>\n"+
			"		<task label=\"weh\" />\n"+
			"	</tasks>\n"+
			"</tasklist>\n";		
		
		loadRequested = false;
		saveRequested = false;
		ms.setRequestHandler(new MockServer.RequestHandler(){
			public void handleRequest(MockServer.Data request, MockServer.Data response)
			{
				try
				{
					if(request.initialLine.split(" +")[1].endsWith("load"))
					{
						loadRequested = true;
						response.headers.put("Content-Type", "text/xml");
						response.headers.put("Timestamp", "2000");
						response.body = data1.getBytes("Utf-8");
								
					}
					else if(request.initialLine.split(" +")[1].endsWith("save"))
					{
						saveRequested = true;
						response.headers.put("Content-Type", "text/xml");
						response.body = data2.getBytes("Utf-8");
					}
				}
				catch(Exception e){
					e.printStackTrace();
				}
			}
		});
		
					
		merged = false;
		
		tt.synchronise(new MergeConfirmer(){
			public boolean confirmMerge(){
				merged = true;
				// just accept client's tree without merging
				return true;
			}
		});			
			
		// should have asked for merge
		assertTrue(merged);			
			
		assertTrue(loadRequested);
		
		assertTrue(saveRequested);
		
		// client's tree should be the same as before
		assertEquals("foo", getNodeLabel(0));
		assertEquals("weh", getNodeLabel(1));
		assertEquals("bar", getNodeLabel(0, 0));		
	}
}
