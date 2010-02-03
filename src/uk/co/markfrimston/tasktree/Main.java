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
import org.apache.http.client.*;
import org.apache.http.*;
import org.apache.http.client.methods.*;
import org.apache.http.entity.*;
import org.apache.http.impl.client.*;

import uk.co.markfrimston.utils.*;

public class Main extends JFrame implements MergeConfirmer
{
	protected TaskTree taskTree;
	
	protected JTree tree;
	protected JTextArea quickIn;
	protected JPopupMenu popup;
	protected JButton syncButton;
	
	public Main(TaskTree taskTree)
	{
		super();
		
		this.taskTree = taskTree;
		
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
						addTask(Main.this.taskTree.getRoot(), 0, newText, true);
						try{
							Main.this.taskTree.changesMade();
						}catch(Exception e){
							error(e.getMessage());
						}
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
		
		this.tree = new JTree(taskTree.getTreeModel());
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
			protected void doSelectRow(MouseEvent arg0)
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
			public void mousePressed(MouseEvent arg0)
			{
				doSelectRow(arg0);
			}
			public void mouseReleased(MouseEvent arg0) 
			{
				doSelectRow(arg0);
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
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					error(ex.getMessage());
				}
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
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					error(ex.getMessage());
				}
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
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					ex.getMessage();
				}
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
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					error(ex.getMessage());
				}
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
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					error(ex.getMessage());
				}
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
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					error(ex.getMessage());
				}
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
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					error(ex.getMessage());
				}
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
					Main.this.taskTree.getTreeModel().reload(selected);
					try{
						Main.this.taskTree.changesMade();
					}catch(Exception ex){
						error(ex.getMessage());
					}
				}
			}
		});
		this.popup.add(rename);
		JMenuItem delete = new JMenuItem("Delete");
		delete.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e)
			{
				promptAndRemove(getSelectedNode());
				try{
					Main.this.taskTree.changesMade();
				}catch(Exception ex){
					error(ex.getMessage());
				}
			}
		});
		this.popup.add(delete);
		
		this.setVisible(true);	
		
		loadConfig();
		load();
		
		syncButton.setVisible(this.taskTree.hasSyncCapability());
	}
	
	protected void loadConfig()
	{
		try
		{
			this.taskTree.loadConfig();
		}
		catch(Exception e)
		{
			error(e.getMessage());
		}
	}
	
	protected void load()
	{
		try
		{
			this.taskTree.load();
			
			// make top level tasks visible
			for(int i=0; i<this.taskTree.getRoot().getChildCount(); i++)
			{
				tree.makeVisible(new TreePath(
						((DefaultMutableTreeNode)this.taskTree.getRoot().getChildAt(i)).getPath()));
			}
		}
		catch(Exception e)
		{
			error(e.getMessage());
		}
	}
	
	protected void moveTask(DefaultMutableTreeNode node, DefaultMutableTreeNode parent, int childPos)
	{
		taskTree.moveTask(node, parent, childPos);		
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
	
	protected DefaultMutableTreeNode addTask(DefaultMutableTreeNode parent, int childPos, 
			String name, boolean show)
	{
		DefaultMutableTreeNode newNode = taskTree.addTask(parent, childPos, name);		
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
			this.taskTree.removeTask(node);
		}
	}
	
	protected void error(String message)
	{
		JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
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
			syncButton.setEnabled(false);
			
			taskTree.synchronise(this);	
			
			// make top level tasks visible
			for(int i=0; i<this.taskTree.getRoot().getChildCount(); i++)
			{
				tree.makeVisible(new TreePath(
						((DefaultMutableTreeNode)this.taskTree.getRoot().getChildAt(i)).getPath()));
			}
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
	
	@Override
	public boolean confirmMerge()
	{
		return JOptionPane.showConfirmDialog(this, "Was the merge completed successfully?",
				"Merge",JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)==JOptionPane.YES_OPTION;
		
	}

	public static void main(String[] args)
	{		
		Main main = new Main(new TaskTree(System.getProperty("user.home")+"/.tasktree/"));
	}
}
