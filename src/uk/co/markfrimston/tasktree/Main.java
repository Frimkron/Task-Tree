/*
Copyright (c) 2009 Mark Frimston

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

import javax.swing.*;
import javax.swing.event.*;

import java.awt.*;
import java.awt.event.*;

import javax.xml.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import javax.swing.tree.*;
import java.io.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import javax.xml.parsers.*;
import java.util.*;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.*;
import uk.co.markfrimston.utils.*;

public class Main extends JFrame
{
	protected static final String FILENAME = "tasks.xml";
	protected static final String MERGE_FILENAME = "merge-temp.xml";
	protected static final String CONFIG_FILENAME = "config.xml";
	protected static DocumentBuilderFactory builderFact = DocumentBuilderFactory.newInstance();
	protected static TransformerFactory transFact = TransformerFactory.newInstance();
	
	protected DefaultMutableTreeNode root;
	protected DefaultTreeModel treeModel;
	protected JTree tree;
	protected JTextArea quickIn;
	protected JPopupMenu popup;
	protected JButton syncButton;
	
	protected boolean unsynchedChanges = true;
	protected String saveUrl;
	protected String loadUrl;
	protected Long lastSyncTime = 0L;
	protected String mergeCommand;
	
	public Main()
	{
		super();
		this.setTitle("Task Tree");
		this.setSize(new Dimension(300,500));
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
		JPanel quickInPanel = new JPanel(new BorderLayout());
		this.quickIn = new JTextArea();
		this.quickIn.addKeyListener(new KeyAdapter(){
			public void keyReleased(KeyEvent arg0) 
			{
				if(arg0.getKeyCode()==KeyEvent.VK_ENTER)
				{
					String newText = quickIn.getText().trim();
					if(newText!=null && newText.length()>0)
					{
						addTask(root, 0, newText, true);
						save();
					}
					quickIn.setText("");
				}
			}			
		});
		this.quickIn.setPreferredSize(new Dimension(300,75));
		this.quickIn.setBorder(BorderFactory.createTitledBorder("Quick Input"));
		quickInPanel.add(this.quickIn, BorderLayout.CENTER);
		this.syncButton = new JButton("Sync");
		this.syncButton.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				new SyncThread(Main.this).start();
			}
		});
		quickInPanel.add(this.syncButton, BorderLayout.EAST);
		this.getContentPane().add(quickInPanel, BorderLayout.NORTH);
		
		root = new DefaultMutableTreeNode("root");
		treeModel = new DefaultTreeModel(root);
		this.tree = new JTree(treeModel);
		DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer(){
			public Component getTreeCellRendererComponent(JTree tree,
					Object value, boolean selected, boolean expanded, boolean leaf,
					int row, boolean hasFocus) 
			{
				DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;
				Object newVal = htmlFilter(String.valueOf(node.getUserObject()));
				if(node.getChildCount()>0 && !tree.isExpanded(new TreePath(node.getPath())))
				{
					DefaultMutableTreeNode firstLeaf = (DefaultMutableTreeNode)node.getFirstLeaf();
					newVal = htmlFilter(String.valueOf(node.getUserObject()))
						+" <span style='color:silver;font-style:italic'>"
							+"("+String.valueOf(firstLeaf.getUserObject())+")</span>";
				}
				newVal = "<html>"+newVal+"</html>";
				
				return super.getTreeCellRendererComponent(tree, newVal, selected, 
						expanded, leaf, row, hasFocus);
			}			
		};
		ImageIcon bulletIcon = new ImageIcon(Main.class.getResource("bullet.gif"));
		renderer.setLeafIcon(bulletIcon);
		renderer.setOpenIcon(bulletIcon);
		renderer.setClosedIcon(bulletIcon);
		renderer.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		this.tree.setCellRenderer(renderer);
		this.tree.setRootVisible(false);	
		this.tree.setShowsRootHandles(true);
		this.tree.addMouseListener(new MouseAdapter(){
			public void mouseReleased(MouseEvent arg0) 
			{
				int row = tree.getRowForLocation(arg0.getX(), arg0.getY());
				if(row != -1)
				{
					tree.setSelectionRow(row);
					if(arg0.isPopupTrigger()){		
						popup.show(tree, arg0.getX(), arg0.getY());
					}	
				}
			}			
		});
		JScrollPane treeScroll = new JScrollPane(tree);
		treeScroll.setBorder(BorderFactory.createTitledBorder("Task List"));
		this.getContentPane().add(treeScroll, BorderLayout.CENTER);		
		
		this.popup = new JPopupMenu();
		JMenuItem addBefore = new JMenuItem("Add Before");
		addBefore.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();			
				DefaultMutableTreeNode parent = (DefaultMutableTreeNode)selected.getParent();
				int pos = parent.getIndex(selected);				
				promptAndInsert(parent, pos);
				save();
			}
		});
		this.popup.add(addBefore);
		JMenuItem addAfter = new JMenuItem("Add After");
		addAfter.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();
				DefaultMutableTreeNode parent = (DefaultMutableTreeNode)selected.getParent();
				int pos = parent.getIndex(selected)+1;
				promptAndInsert(parent, pos);
				save();
			}
		});
		this.popup.add(addAfter);
		JMenuItem addNested= new JMenuItem("Add Nested");
		addNested.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();
				int pos = selected.getChildCount();
				promptAndInsert(selected, pos);
				save();
			}
		});
		this.popup.add(addNested);
		this.popup.add(new JSeparator());
		JMenuItem moveTop = new JMenuItem("Move to Top");
		moveTop.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();
				DefaultMutableTreeNode parent = (DefaultMutableTreeNode)selected.getParent();
				moveTask(selected, parent, 0);
				save();
			}
		});
		this.popup.add(moveTop);
		JMenuItem moveUp = new JMenuItem("Move Up");
		moveUp.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();
				DefaultMutableTreeNode parent = (DefaultMutableTreeNode)selected.getParent();
				int pos = Math.max(parent.getIndex(selected)-1,0);
				moveTask(selected, parent, pos);
				save();
			}
		});
		this.popup.add(moveUp);
		JMenuItem moveDown = new JMenuItem("Move Down");
		moveDown.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();
				DefaultMutableTreeNode parent = (DefaultMutableTreeNode)selected.getParent();
				int pos = Math.min(parent.getIndex(selected)+1, parent.getChildCount()-1);
				moveTask(selected, parent, pos);
				save();
			}
		});
		this.popup.add(moveDown);
		JMenuItem moveBottom = new JMenuItem("Move to Bottom");
		moveBottom.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();
				DefaultMutableTreeNode parent = (DefaultMutableTreeNode)selected.getParent();				
				moveTask(selected, parent, parent.getChildCount()-1);
				save();
			}
		});
		this.popup.add(moveBottom);
		this.popup.add(new JSeparator());
		JMenuItem rename = new JMenuItem("Edit");
		rename.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				DefaultMutableTreeNode selected = getSelectedNode();
				String newText = prompt((String)selected.getUserObject());
				if(newText!=null && newText.length()>0)
				{			
					selected.setUserObject(newText);
					treeModel.reload(selected);
					save();
				}
			}
		});
		this.popup.add(rename);
		JMenuItem delete = new JMenuItem("Delete");
		delete.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				promptAndRemove(getSelectedNode());
				save();
			}
		});
		this.popup.add(delete);
		
		this.setVisible(true);	
		
		loadConfig();
		load();
		
		syncButton.setVisible(loadUrl!=null && saveUrl!=null 
				&& mergeCommand!=null);
	}
	
	protected String htmlFilter(String input)
	{
		return input
			.replaceAll("&","&amp;")
			.replaceAll("<","&lt;")
			.replaceAll(">", "&gt;")
			.replaceAll("\"", "&quot;");
	}
	
	protected DefaultMutableTreeNode getSelectedNode()
	{
		int[] selected = tree.getSelectionRows();
		if(selected==null || selected.length==0){
			return null;
		}
		TreePath path = tree.getPathForRow(selected[0]);
		if(path==null || path.getPathCount()==0){
			return null;
		}
		return (DefaultMutableTreeNode)path.getLastPathComponent();
	}
	
	protected String prompt(String existing)
	{
		return JOptionPane.showInputDialog(this,"Enter label",existing);
	}
	
	protected void promptAndInsert(DefaultMutableTreeNode parent, int childPos)
	{
		String nodeText = prompt("");
		if(nodeText!=null && nodeText.length()>0)
		{
			addTask(parent, childPos, nodeText, true);
		}		
	}
	
	protected void moveTask(DefaultMutableTreeNode node, DefaultMutableTreeNode parent, int childPos)
	{
		treeModel.removeNodeFromParent(node);
		treeModel.insertNodeInto(node, parent, childPos);
	}
	
	protected DefaultMutableTreeNode addTask(DefaultMutableTreeNode parent, int childPos, 
			String name, boolean show)
	{
		DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(name);
		treeModel.insertNodeInto(newNode, parent, childPos);
		if(show){
			tree.makeVisible(new TreePath(newNode.getPath()));
		}
		return newNode;
	}
	
	protected void promptAndRemove(DefaultMutableTreeNode node)
	{
		boolean canRemove = true;
		if(node.getChildCount()>0)
		{
			int resp = JOptionPane.showConfirmDialog(this,"Item contains nested items. Remove anyway?");
			if(resp != JOptionPane.YES_OPTION){
				canRemove = false;
			}
		}
		if(canRemove)
		{
			removeTask(node);
		}
	}
	
	protected void removeTask(DefaultMutableTreeNode node)
	{
		treeModel.removeNodeFromParent(node);
	}
	
	protected void writeDocToStream(Document doc, OutputStream stream) throws Exception
	{
		transFact.setAttribute("indent-number", 4);
		Transformer trans = transFact.newTransformer();
		trans.setOutputProperty(OutputKeys.INDENT, "yes");		
		OutputStreamWriter writer = new OutputStreamWriter(stream,"UTF-8");
		trans.transform(new DOMSource(doc), new StreamResult(writer));
	}
	
	protected Document saveToDocument() throws Exception
	{
		DocumentBuilder builder = builderFact.newDocumentBuilder();
		Document doc = builder.newDocument();
		Element taskList = doc.createElement("tasklist");
		doc.appendChild(taskList);			
		Element tasks = doc.createElement("tasks");
		taskList.appendChild(tasks);
		addChildElementsFromTasks(doc,tasks,root);
		return doc;
	}
	
	protected void save()
	{
		try
		{
			Document doc = saveToDocument();
			FileOutputStream fileStream = new FileOutputStream(new File(FILENAME));
			writeDocToStream(doc, fileStream);
			fileStream.close();
		}
		catch(Exception e)
		{
			error("Failed to save file: "+e.getClass().getName()+" - "+e.getMessage());
		}		
	}
	
	protected void addChildElementsFromTasks(Document doc, Element parent, 
			DefaultMutableTreeNode treeNode)
	{
		for(int i=0; i<treeNode.getChildCount(); i++)
		{
			DefaultMutableTreeNode treeChild = (DefaultMutableTreeNode)treeNode.getChildAt(i);
			Element childEl = doc.createElement("task");
			childEl.setAttribute("label", (String)treeChild.getUserObject());
			parent.appendChild(childEl);
			addChildElementsFromTasks(doc, childEl, treeChild);
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
		addTasksFromChildElements(tasks, this.root, true);
	}
	
	protected void load()
	{
		try
		{
			File file = new File(FILENAME);
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
			error("Failed to load file: "+e.getClass().getName()+" - "+e.getMessage());
		}
	}
	
	protected void addTasksFromChildElements(Element parent, DefaultMutableTreeNode treeNode,
			boolean show)
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
			DefaultMutableTreeNode newNode = addTask(treeNode, treeNode.getChildCount(), name, show);
			addTasksFromChildElements(child, newNode, false);
		}
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
	
	protected Iterator<Element> getElementChildren(Node parent)
	{
		return new ElChildIterator(parent);
	}
	
	protected void error(String message)
	{
		JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
	}
	
	protected void clearTree()
	{
		root.removeAllChildren();
		treeModel.reload();
	}
	
	protected void loadConfig()
	{
		try
		{
			File file = new File(CONFIG_FILENAME);
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
			}
		}
		catch(Exception e)
		{
			error("Failed to load config file: "+e.getClass().getName()+" - "+e.getMessage());
		}
	}
	
	protected void saveConfig()
	{
		try
		{
			DocumentBuilder builder = builderFact.newDocumentBuilder();
			Document doc = builder.newDocument();
			Element elConfig = doc.createElement("config");
			doc.appendChild(elConfig);			
			
			if(loadUrl != null)
			{
				Element elLoadUrl = doc.createElement("load-url");
				elLoadUrl.appendChild(doc.createTextNode(loadUrl));
				elConfig.appendChild(elLoadUrl);
			}
			if(saveUrl != null)
			{
				Element elSaveUrl = doc.createElement("save-url");
				elSaveUrl.appendChild(doc.createTextNode(saveUrl));
				elConfig.appendChild(elSaveUrl);
			}
			if(mergeCommand != null)
			{
				Element elMergeCommand = doc.createElement("merge-command");
				elMergeCommand.appendChild(doc.createTextNode(mergeCommand));
				elConfig.appendChild(elMergeCommand);
			}
			if(lastSyncTime == null){
				lastSyncTime = 0L;
			}
			Element elSync = doc.createElement("last-sync");
			elSync.appendChild(doc.createTextNode(String.valueOf(lastSyncTime)));
			elConfig.appendChild(elSync);
			
			FileOutputStream fileStream = new FileOutputStream(new File(CONFIG_FILENAME));
			writeDocToStream(doc,fileStream);
			fileStream.close();					
		}
		catch(Exception e)
		{
			error("Failed to save config file: "+e.getClass().getName()+" - "+e.getMessage());
		}
	}
	
	public static class SyncThread extends Thread
	{
		protected Main main;
		
		public SyncThread(Main main){
			this.main = main;
		}
		
		public void run()
		{			
			main.synchronise();
		}
	}
	
	protected void synchronise()
	{
		try
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
			
			syncButton.setEnabled(false);
			
			Long newTimestamp = new Date().getTime(); 
			
			HttpClient client = new HttpClient();
			DocumentBuilderFactory builderFact = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = builderFact.newDocumentBuilder();			
			
			// make load request
			PostMethod post = new PostMethod(loadUrl);			
			client.executeMethod(post);
			if(post.getStatusCode()!=200){
				throw new Exception("Unexpected load response from server: "+post.getStatusCode()
						+" "+post.getStatusText());
			}
			
			// get timestamp header
			Header tsHeader = post.getRequestHeader("timestamp");
			Long timestamp;
			if(tsHeader==null){
				throw new Exception("Missing timestamp from server");
			}			
			try{
				timestamp = Long.parseLong(tsHeader.getValue());
			}catch(NumberFormatException e){
				throw new Exception("Invalid timestamp from server \""+tsHeader.getValue()+"\"");
			}			
			if(timestamp < lastSyncTime){
				throw new Exception("Remote timestamp earlier than local timestamp");
			}
			
			// parse xml	
			Document doc;
			try{
				doc = builder.parse(post.getResponseBodyAsStream());
			}catch(Exception e){
				throw new Exception("Failed to parse load response from server");
			}
			
			// if remote version is more up to date
			if(timestamp > lastSyncTime)
			{
				// if local changes made
				if(unsynchedChanges)
				{
					// merge.
					
					// save local tree
					save();
					
					// save remote tree to temp file
					FileOutputStream fileStream = new FileOutputStream(MERGE_FILENAME);
					writeDocToStream(doc,fileStream);
					fileStream.close();
					
					// execute merge command to perform merge
					String commandString = StringUtils.template(mergeCommand, 
							FILENAME, MERGE_FILENAME );
					Process proc = Runtime.getRuntime().exec(commandString);
					proc.waitFor();
					proc.destroy();
					
					if(JOptionPane.showConfirmDialog(this, "Was the merge completed successfully?",
							"Merge",JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)!=JOptionPane.YES_OPTION)
					{
						throw new Exception("Merge aborted");
					}
					
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
			
			// if local changes made
			if(unsynchedChanges)
			{
				// write xml to byte array
				doc = saveToDocument();
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				writeDocToStream(doc,baos);
				baos.close();

				// make save request				
				post = new PostMethod(saveUrl);
				post.setRequestHeader("timestamp",String.valueOf(newTimestamp));
				ByteArrayRequestEntity bare = new ByteArrayRequestEntity(baos.toByteArray(),"application/xml");
				post.setRequestEntity(bare);
				client.executeMethod(post);
				if(post.getStatusCode()!=200){
					throw new Exception("Unexpected save response from server: "+post.getStatusCode()
							+" "+post.getStatusText());
				}				
				
				// server should echo back same xml to confirm
				Document echoDoc;
				try{
					echoDoc = builder.parse(post.getResponseBodyAsStream());
				}catch(Exception e){
					throw new Exception("Failed to parse save response from server");
				}
				if(!nodesEqual(doc,echoDoc)){
					throw new Exception("Bad save response from server");
				}				
			}
			
			unsynchedChanges = false;
			lastSyncTime = newTimestamp;
			
			// save config
			saveConfig();
		}
		catch(Exception e)
		{
			error("Failed to synchronise: "+e.getClass().getName()+" - "+e.getMessage());
		}
		finally
		{
			syncButton.setEnabled(true);
		}		
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
	
	public static void main(String[] args)
	{		
		Main main = new Main();
		/*try
		{
			String inputA = 
				"<test><foo one=\"1\" two=\"2\"><bar/>foo<bar/><bar/></foo></test>";
			String inputB = 
				"<test><foo one=\"1\" two=\"2\"><bar/>foo<bar/><bar/></foo></test>";		
			DocumentBuilder builder = builderFact.newDocumentBuilder();
			Document docA = builder.parse(new ByteArrayInputStream(inputA.getBytes("UTF-8")));			
			Document docB = builder.parse(new ByteArrayInputStream(inputB.getBytes("UTF-8")));
			System.out.println(nodesEqual(docA,docB));
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}*/
		/*try
		{
			System.out.println("Starting");
			Process p = Runtime.getRuntime().exec(
					"\"C:\\program files\\SourceGear\\Diffmerge\\Diffmerge.exe\" "
					+"-t1=local -t2=remote file_a.txt file_b.txt");
			p.waitFor();
			p.destroy();
			System.out.println("Finished");
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}*/
	}
}
