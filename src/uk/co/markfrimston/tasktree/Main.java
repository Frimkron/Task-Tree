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

public class Main extends JFrame
{
	protected static final String FILENAME = "tasks.xml";
	protected static DocumentBuilderFactory builderFact = DocumentBuilderFactory.newInstance();
	protected static TransformerFactory transFact = TransformerFactory.newInstance();
	
	protected DefaultMutableTreeNode root;
	protected DefaultTreeModel treeModel;
	protected JTree tree;
	protected JTextArea quickIn;
	protected JPopupMenu popup;
	
	public Main()
	{
		super();
		this.setTitle("Task Tree");
		this.setSize(new Dimension(300,500));
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
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
		this.getContentPane().add(quickIn, BorderLayout.NORTH);
		
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
		
		load();
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
	
	protected void save()
	{
		try
		{
			DocumentBuilder builder = builderFact.newDocumentBuilder();
			Document doc = builder.newDocument();
			Element taskList = doc.createElement("tasklist");
			doc.appendChild(taskList);			
			Element tasks = doc.createElement("tasks");
			taskList.appendChild(tasks);
			addChildElementsFromTasks(doc,tasks,root);
			transFact.setAttribute("indent-number", 4);
			Transformer trans = transFact.newTransformer();
			trans.setOutputProperty(OutputKeys.INDENT, "yes");
			FileOutputStream fileStream = new FileOutputStream(new File(FILENAME));
			OutputStreamWriter fileWriter = new OutputStreamWriter(fileStream,"UTF-8");
			trans.transform(new DOMSource(doc), new StreamResult(fileWriter));
			fileWriter.close();
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
	
	public static void main(String[] args)
	{		
		Main main = new Main();
	}
}
