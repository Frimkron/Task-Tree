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

package uk.co.markfrimston.tasktree;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import uk.co.markfrimston.utils.StringUtils;
import uk.co.markfrimston.utils.XmlPrologBreakFilterWriter;

public class TaskTree
{
	protected static final String FILENAME = "tasks.xml";
	protected static final String MERGE_FILENAME = "merge-temp.xml";
	protected static final String CONFIG_FILENAME = "config.xml";
	protected static DocumentBuilderFactory builderFact = DocumentBuilderFactory.newInstance();
	protected static TransformerFactory transFact = TransformerFactory.newInstance();
	
	protected DefaultMutableTreeNode root;
	protected DefaultTreeModel treeModel;
	
	protected String filePath;
	protected String saveUrl;
	protected String loadUrl;
	protected Long lastSyncTime = 0L;
	protected Boolean unsynchedChanges = true;
	protected String mergeCommand;
	
	public TaskTree(String filePath)
	{
		this.filePath = filePath;
		
		this.root = new DefaultMutableTreeNode("root");
		this.treeModel = new DefaultTreeModel(root);
	}
	
	public String getSaveUrl()
	{
		return saveUrl;
	}

	public void setSaveUrl(String saveUrl)
	{
		this.saveUrl = saveUrl;
	}

	public String getLoadUrl()
	{
		return loadUrl;
	}

	public void setLoadUrl(String loadUrl)
	{
		this.loadUrl = loadUrl;
	}

	public Long getLastSyncTime()
	{
		return lastSyncTime;
	}

	public void setLastSyncTime(Long lastSyncTime)
	{
		this.lastSyncTime = lastSyncTime;
	}

	public Boolean getUnsynchedChanges()
	{
		return unsynchedChanges;
	}

	public void setUnsynchedChanges(Boolean unsynchedChanges)
	{
		this.unsynchedChanges = unsynchedChanges;
	}

	public String getMergeCommand()
	{
		return mergeCommand;
	}

	public void setMergeCommand(String mergeCommand)
	{
		this.mergeCommand = mergeCommand;
	}

	public DefaultMutableTreeNode getRoot()
	{
		return root;
	}
	
	public void setRoot(DefaultMutableTreeNode root)
	{
		this.root = root;
	}
	
	public DefaultTreeModel getTreeModel()
	{
		return treeModel;
	}
	
	public void changesMade() throws Exception
	{
		unsynchedChanges = true;
		save();
		saveConfig();
	}
	
	public DefaultMutableTreeNode addTask(DefaultMutableTreeNode parent, int childPos, 
			String name)
	{
		DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(name);
		treeModel.insertNodeInto(newNode, parent, childPos);
		return newNode;
	}
	
	public void synchronise(MergeConfirmer mergeConfirmer) throws Exception
	{
		if(loadUrl==null){
			throw new Exception("No load URL defined");
		}
		if(saveUrl==null){
			throw new Exception("No save URL defined");
		}
		if(mergeCommand==null){
			throw new Exception("No merge command defined");
		}
		
		Long newTimestamp = new Date().getTime(); 
		
		HttpClient client = new DefaultHttpClient();
		DocumentBuilderFactory builderFact = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = builderFact.newDocumentBuilder();			
		
		// make load request
		HttpPost post = new HttpPost(loadUrl);			
		HttpResponse response = client.execute(post);			
		if(response.getStatusLine().getStatusCode()!=200){
			throw new Exception("Unexpected load response from server: "
					+response.getStatusLine().getStatusCode()
					+" "+response.getStatusLine().getReasonPhrase());
		}
		
		// get timestamp header
		Header[] tsHeaders = response.getHeaders("Timestamp");
		Long timestamp;
		if(tsHeaders==null || tsHeaders.length<1){
			throw new Exception("Missing timestamp from server");
		}			
		try{
			timestamp = Long.parseLong(tsHeaders[0].getValue());
		}catch(NumberFormatException e){
			throw new Exception("Invalid timestamp from server \""+tsHeaders[0].getValue()+"\"");
		}			
		if(timestamp!=0 && timestamp < lastSyncTime){
			throw new Exception("Remote timestamp earlier than local timestamp");
		}
		
		// parse xml	
		Document doc;			
		try{
			doc = builder.parse(response.getEntity().getContent());
		}catch(Exception e){
			throw new Exception("Failed to parse load response from server");
		}
		
		// if remote version is more up to date
		if(timestamp > lastSyncTime)
		{
			// if local changes made, merge
			if(unsynchedChanges)
			{					
				// save local tree
				save();
				
				// save remote tree to temp file
				makeFilePath();
				FileOutputStream fileStream = new FileOutputStream(filePath+MERGE_FILENAME);
				writeDocToStream(doc,fileStream);
				fileStream.close();
				
				// execute merge command to perform merge
				String commandString = StringUtils.template(mergeCommand, 
						filePath+FILENAME, filePath+MERGE_FILENAME );
				Process proc = Runtime.getRuntime().exec(commandString);
				proc.waitFor();
				proc.destroy();
				
				if(!mergeConfirmer.confirmMerge())
				{
					throw new Exception("Merge aborted");
				}
				
				// remove temp file
				new File(filePath+MERGE_FILENAME).delete();
				
				// load the newly merged local tree
				load();
			}
			else
			{
				// just load xml from remote
				loadFromDocument(doc);
				
				// save to file
				save();
			}
		}
		
		// save back to remote every time, to update remote with new sync timestamp.
				
		// write xml to byte array
		doc = saveToDocument();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		writeDocToStream(doc,baos);
		baos.close();

		// make save request				
		post = new HttpPost(saveUrl);
		post.addHeader("Timestamp",String.valueOf(newTimestamp));
		ByteArrayEntity bare = new ByteArrayEntity(baos.toByteArray());
		bare.setContentType("application/xml");
		post.setEntity(bare);
		response = client.execute(post);
		if(response.getStatusLine().getStatusCode()!=200){
			throw new Exception("Unexpected save response from server: "
					+response.getStatusLine().getStatusCode()
					+" "+response.getStatusLine().getReasonPhrase());
		}				
		
		// server should echo back same xml to confirm
		Document echoDoc;
		try{
			echoDoc = builder.parse(response.getEntity().getContent());
		}catch(Exception e){
			throw new Exception("Failed to parse save response from server");
		}
		if(!nodesEqual(doc,echoDoc)){
			throw new Exception("Bad save response from server");
		}						
		
		unsynchedChanges = false;
		lastSyncTime = newTimestamp;
		
		// save config
		saveConfig();
	}
	
	public void save() throws Exception
	{
		try
		{
			Document doc = saveToDocument();
			makeFilePath();
			File file = new File(filePath+FILENAME);
			FileOutputStream fileStream = new FileOutputStream(file);
			writeDocToStream(doc, fileStream);
			fileStream.close();
		}
		catch(Exception e)
		{
			throw new Exception("Failed to save file: "+e.getClass().getName()+" - "+e.getMessage());
		}		
	}
	
	protected Document saveToDocument() throws Exception
	{
		DocumentBuilder builder = builderFact.newDocumentBuilder();
		Document doc = builder.newDocument();
		Element taskList = doc.createElement("tasklist");
		doc.appendChild(taskList);		
		taskList.appendChild(doc.createTextNode("\n\t"));
		Element tasks = doc.createElement("tasks");
		taskList.appendChild(tasks);
		taskList.appendChild(doc.createTextNode("\n"));
		addChildElementsFromTasks(doc,tasks,root,1);
		
		return doc;
	}
	
	protected void addChildElementsFromTasks(Document doc, Element parent, 
			DefaultMutableTreeNode treeNode, int indent)
	{
		for(int i=0; i<treeNode.getChildCount(); i++)
		{
			DefaultMutableTreeNode treeChild = (DefaultMutableTreeNode)treeNode.getChildAt(i);
			String indentText = "\n";
			for(int j=0; j<indent+1; j++){
				indentText += "\t";
			}
			parent.appendChild(doc.createTextNode(indentText));
			Element childEl = doc.createElement("task");
			childEl.setAttribute("label", (String)treeChild.getUserObject());
			parent.appendChild(childEl);
			addChildElementsFromTasks(doc, childEl, treeChild, indent+1);
		}		
		if(treeNode.getChildCount()>0){
			String indentText = "\n";
			for(int i=0; i<indent; i++){
				indentText += "\t";
			}
			parent.appendChild(doc.createTextNode(indentText));
		}
	}
	
	protected void makeFilePath()
	{
		File path = new File(filePath);
		if(!path.exists()){
			path.mkdirs();
		}
	}
	
	protected void writeDocToStream(Document doc, OutputStream stream) throws Exception
	{
		Transformer trans = transFact.newTransformer();
		OutputStreamWriter writer = new OutputStreamWriter(stream,"UTF-8");
		trans.transform(new DOMSource(doc), new StreamResult(new XmlPrologBreakFilterWriter(writer)));
	}
	
	protected boolean nodesEqual(Node a, Node b)
	{
		if((a==null)!=(b==null)){
			return false;
		}
		if(a!=null)
		{
			if(a.getNodeType()!=b.getNodeType()){
				return false;
			}
			if((a.getNodeName()==null)!=(b.getNodeName()==null)){
				return false;
			}
			if(a.getNodeName()!=null && !a.getNodeName().equals(b.getNodeName())){
				return false;
			}
			if((a.getNodeValue()==null)!=(b.getNodeValue()==null)){
				return false;
			}
			if(a.getNodeValue()!=null && !a.getNodeValue().equals(b.getNodeValue())){
				return false;
			}
			NamedNodeMap attrsA = a.getAttributes();
			Map<String,String> attrMapA = new HashMap<String,String>();
			if(attrsA!=null)
			{
				for(int i=0; i<attrsA.getLength(); i++)
				{
					Attr att = (Attr)attrsA.item(i);
					attrMapA.put(att.getName(),att.getValue());
				}
			}
			NamedNodeMap attrsB = b.getAttributes();
			Map<String,String> attrMapB = new HashMap<String,String>();
			if(attrsB!=null)
			{
				for(int i=0; i<attrsB.getLength(); i++)
				{
					Attr att = (Attr)attrsB.item(i);
					attrMapB.put(att.getName(),att.getValue());
				}
			}
			if(!attrMapA.equals(attrMapB)){
				return false;
			}
			
			Node childA = a.getFirstChild();
			Node childB = b.getFirstChild();
			while(childA!=null)
			{
				if(!nodesEqual(childA,childB)){
					return false;
				}
				childA = childA.getNextSibling();
				childB = childB.getNextSibling();
			}
			if(childB!=null){
				return false;
			}
		}
		return true;
	}
	
	public void saveConfig() throws Exception
	{
		try
		{
			DocumentBuilder builder = builderFact.newDocumentBuilder();
			Document doc = builder.newDocument();
			//doc.appendChild(doc.createTextNode("\n"));
			Element elConfig = doc.createElement("config");
			doc.appendChild(elConfig);	
			elConfig.appendChild(doc.createTextNode("\n"));
			
			makeConfigEl(doc,elConfig,"load-url","URL used to load task tree from remote server",loadUrl);
			
			makeConfigEl(doc,elConfig,"save-url","URL used to save task tree to remote server",saveUrl);
			
			makeConfigEl(doc,elConfig,"merge-command","Command executed to merge task tree versions. "
						+"Use {0} for local file, {1} for remote",mergeCommand);								
			
			if(lastSyncTime == null){
				lastSyncTime = 0L;
			}
			makeConfigEl(doc,elConfig,"last-sync","Timestamp of last sync. Do not edit!",lastSyncTime);			
			
			if(unsynchedChanges == null){
				unsynchedChanges = true;
			}
			makeConfigEl(doc,elConfig,"unsynched-changes","Changes made since last sync. Do not edit!",unsynchedChanges);
			
			elConfig.appendChild(doc.createTextNode("\n"));
			
			makeFilePath();
			File file = new File(filePath+CONFIG_FILENAME);
			FileOutputStream fileStream = new FileOutputStream(file);
			writeDocToStream(doc,fileStream);
			fileStream.close();					
		}
		catch(Exception e)
		{
			throw new Exception("Failed to save config file: "+e.getClass().getName()+" - "+e.getMessage());
		}
	}
	
	protected void makeConfigEl(Document doc, Node parent, String name, String description, Object value)
	{
		parent.appendChild(doc.createTextNode("\n\t"));
		parent.appendChild(doc.createComment(description));
		parent.appendChild(doc.createTextNode("\n\t"));
		Element elParam = doc.createElement(name);
		if(value!=null){
			elParam.appendChild(doc.createTextNode(String.valueOf(value)));
		}
		parent.appendChild(elParam);
		parent.appendChild(doc.createTextNode("\n"));
	}
	
	public void load() throws Exception
	{
		try
		{
			File file = new File(filePath+FILENAME);
			if(!file.exists())
			{
				save();
			}
			DocumentBuilder builder = builderFact.newDocumentBuilder();
			Document doc = builder.parse(file);
			loadFromDocument(doc);
		}
		catch(Exception e)
		{
			throw new Exception("Failed to load file: "+e.getClass().getName()+" - "+e.getMessage());
		}
	}
	
	protected void loadFromDocument(Document doc) throws Exception
	{
		Element root = doc.getDocumentElement();
		if(root==null || !root.getNodeName().equals("tasklist"))
		{
			throw new Exception("Missing root element \"tasklist\"");
		}
		Iterator<Element> i = getElementChildren(root);
		if(!i.hasNext())
		{
			throw new Exception("Missing element \"tasks\""); 
		}
		clearTree();
		Element tasks = i.next();
		if(!tasks.getNodeName().equals("tasks")){
			throw new Exception("Missing element \"tasks\"");
		}
		addTasksFromChildElements(tasks, this.root);
	}
	
	protected Iterator<Element> getElementChildren(Node parent)
	{
		return new ElChildIterator(parent);
	}
	
	protected class ElChildIterator implements Iterator<Element>
	{
		protected Node parent;
		protected Element next;
		int pos = 0;
		
		public ElChildIterator(Node parent)
		{
			this.parent = parent;
			this.pos = 0;
			this.next = null;
			windOn();
		}
		
		protected void windOn()
		{			
			while(this.pos < parent.getChildNodes().getLength() 
					&& parent.getChildNodes().item(this.pos).getNodeType()!=Node.ELEMENT_NODE)
			{
				this.pos ++;
			}
			if(this.pos < parent.getChildNodes().getLength()){
				this.next = (Element)parent.getChildNodes().item(this.pos);
				this.pos ++;
			}else{
				this.next = null;
			}
		}
		
		public boolean hasNext() 
		{
			return next!=null;
		}

		public Element next() 
		{
			Element ret = next;			
			windOn();
			return ret;
		}

		public void remove() 
		{
			throw new UnsupportedOperationException();
		}		
	}
	
	protected void clearTree()
	{
		root.removeAllChildren();
		treeModel.reload();
	}
	
	protected void addTasksFromChildElements(Element parent, DefaultMutableTreeNode treeNode)
		throws Exception
	{
		Iterator<Element> i = getElementChildren(parent);
		while(i.hasNext())
		{
			Element child = i.next();
			if(!child.getNodeName().equals("task")){
				throw new Exception("Expected \"task\", found \""+child.getNodeName()+"\"");
			}
			String name = child.getAttribute("label");
			if(name==null || name.length()==0){
				throw new Exception("No label attribute for task");
			}
			DefaultMutableTreeNode newNode = addTask(treeNode, treeNode.getChildCount(), name);
			addTasksFromChildElements(child, newNode);
		}
	}
	
	public void loadConfig() throws Exception
	{
		try
		{
			File file = new File(filePath+CONFIG_FILENAME);
			if(!file.exists())
			{
				saveConfig();
			}
			DocumentBuilder builder = builderFact.newDocumentBuilder();
			Document doc = builder.parse(file);
			Element root = doc.getDocumentElement();
			if(root==null || !root.getNodeName().equals("config"))
			{
				throw new Exception("Missing root element \"config\"");
			}
			Iterator<Element> i = getElementChildren(root);						
			
			loadUrl = null;
			saveUrl = null;
			mergeCommand = null;
			lastSyncTime = 0L;
			unsynchedChanges = true;
						
			while(i.hasNext())
			{
				Element el = i.next();
				
				if(el.getNodeName().equals("load-url")){
					loadUrl = el.getTextContent();
					if(loadUrl!=null && loadUrl.length()==0){
						loadUrl = null;
					}
				}
				else if(el.getNodeName().equals("save-url")){
					saveUrl = el.getTextContent();
					if(saveUrl!=null && saveUrl.length()==0){
						saveUrl = null;
					}
				}
				else if(el.getNodeName().equals("merge-command")){
					mergeCommand = el.getTextContent();
					if(mergeCommand!=null && mergeCommand.length()==0){
						mergeCommand = null;
					}
				}
				else if(el.getNodeName().equals("last-sync")){
					try{
						lastSyncTime = Long.parseLong(el.getTextContent());
					}catch(NumberFormatException e){}
				}		
				else if(el.getNodeName().equals("unsynched-changes")){					
					unsynchedChanges = Boolean.parseBoolean(el.getTextContent());					
				}
			}
		}
		catch(Exception e)
		{
			throw new Exception("Failed to load config file: "+e.getClass().getName()+" - "+e.getMessage());
		}
	}
	
	public boolean hasSyncCapability()
	{
		return loadUrl!=null && saveUrl!=null && mergeCommand!=null;
	}
	
	public void moveTask(DefaultMutableTreeNode node, DefaultMutableTreeNode parent, int childPos)
	{
		treeModel.removeNodeFromParent(node);
		treeModel.insertNodeInto(node, parent, childPos);
	}
	
	public void removeTask(DefaultMutableTreeNode node)
	{
		treeModel.removeNodeFromParent(node);
	}
}
