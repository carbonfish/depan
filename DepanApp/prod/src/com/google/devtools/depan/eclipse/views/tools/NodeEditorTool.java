/*
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.depan.eclipse.views.tools;

import com.google.devtools.depan.eclipse.editors.NodeWrapperTreeSorter;
import com.google.devtools.depan.eclipse.editors.ViewEditor;
import com.google.devtools.depan.eclipse.trees.GraphData;
import com.google.devtools.depan.eclipse.trees.NodeTreeProvider;
import com.google.devtools.depan.eclipse.trees.NodeTreeView;
import com.google.devtools.depan.eclipse.trees.NodeTreeView.NodeWrapper;
import com.google.devtools.depan.eclipse.utils.DefaultRelationshipSet;
import com.google.devtools.depan.eclipse.utils.EditColTableDef;
import com.google.devtools.depan.eclipse.utils.LabeledControl;
import com.google.devtools.depan.eclipse.utils.RelationshipSelectorListener;
import com.google.devtools.depan.eclipse.utils.RelationshipSetPickerControl;
import com.google.devtools.depan.eclipse.utils.Resources;
import com.google.devtools.depan.eclipse.utils.Tools;
import com.google.devtools.depan.eclipse.views.NodeEditorLabelProvider;
import com.google.devtools.depan.eclipse.views.ViewSelectionListenerTool;
import com.google.devtools.depan.graph.api.DirectedRelationFinder;
import com.google.devtools.depan.model.GraphNode;
import com.google.devtools.depan.model.RelationshipSet;
import com.google.devtools.depan.util.StringUtils;
import com.google.devtools.depan.view.NodeDisplayProperty;
import com.google.devtools.depan.view.NodeDisplayProperty.Size;

import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.CheckboxCellEditor;
import org.eclipse.jface.viewers.ComboBoxCellEditor;
import org.eclipse.jface.viewers.ICellModifier;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

import java.awt.Color;

/**
 * Tool for Node edition. Associate to each node a {@link NodeDisplayProperty}
 * and provide a GUI to edit them.
 *
 * @author ycoppel@google.com (Yohann Coppel)
 *
 */
public class NodeEditorTool extends ViewSelectionListenerTool
    implements ICellModifier, NodeTreeProvider<NodeDisplayProperty>,
    RelationshipSelectorListener {

  /**
   * Node Tree View handling the TreeViewer, and the data inside.
   */
  private NodeTreeView<NodeDisplayProperty> nodeTreeView = null;

  /**
   * Selector for named relationships sets.
   */
  private RelationshipSetPickerControl relationshipSetPicker = null;

  protected static final String COL_NAME = "Name";
  protected static final String COL_VISIBLE = "Visible";
  protected static final String COL_SELECTED = "Selected";
  protected static final String COL_SIZE = "Size";
  protected static final String COL_COLOR = "Color (R,G,B - empty = default)";

  public static final int INDEX_NAME = 0;
  public static final int INDEX_VISIBLE = 1;
  public static final int INDEX_SELECTED = 2;
  public static final int INDEX_SIZE = 3;
  public static final int INDEX_COLOR = 4;

  private static final EditColTableDef[] TABLE_DEF = new EditColTableDef[] {
    new EditColTableDef(COL_NAME, false, COL_NAME, 180),
    new EditColTableDef(COL_VISIBLE, true, COL_VISIBLE, 20),
    new EditColTableDef(COL_SELECTED, true, COL_SELECTED, 20),
    new EditColTableDef(COL_SIZE, true, COL_SIZE, 80),
    new EditColTableDef(COL_COLOR, true, COL_COLOR, 40)
  };

  @Override
  public Image getIcon() {
    return Resources.IMAGE_NODEEDITOR;
  }

  @Override
  public String getName() {
    return Resources.NAME_NODEEDITOR;
  }

  @Override
  public void setEditor(ViewEditor viewEditor) {
    if (hasEditor()) {
      GraphData<NodeDisplayProperty> hierarchy = getEditorHierarchy();
      hierarchy.saveExpandState(
          nodeTreeView.getTreeViewer().getExpandedTreePaths());
    }

    super.setEditor(viewEditor);
    refresh();
  }

  @Override
  public Control setupComposite(Composite parent) {
    Composite baseComposite = new Composite(parent, SWT.NONE);
    GridLayout grid = new GridLayout(1, false);
    baseComposite.setLayout(grid);

    setupRelationPicker(baseComposite);

    nodeTreeView = new NodeTreeView<NodeDisplayProperty>(baseComposite,
        SWT.VIRTUAL | SWT.FULL_SELECTION | SWT.BORDER);

    nodeTreeView.getTreeViewer().getControl().setLayoutData(
        new GridData(SWT.FILL, SWT.FILL, true, true));

    Tree tree = nodeTreeView.getTreeViewer().getTree();
    tree.setHeaderVisible(true);

    for (EditColTableDef d : TABLE_DEF) {
      TreeColumn col = new TreeColumn(tree, SWT.LEFT);
      col.setText(d.getLabel());
      col.setWidth(d.getWidth());
    }

    CellEditor[] cellA = new CellEditor[5];
    cellA[INDEX_NAME] = null;
    cellA[INDEX_VISIBLE] = new CheckboxCellEditor(tree);
    cellA[INDEX_SELECTED] = new CheckboxCellEditor(tree);
    cellA[INDEX_SIZE] = new ComboBoxCellEditor(tree,
        Tools.toString(Size.values(), true));
    cellA[INDEX_COLOR] = new TextCellEditor(tree);

    nodeTreeView.getTreeViewer().setCellEditors(cellA);
    nodeTreeView.getTreeViewer().setColumnProperties(
        EditColTableDef.getProperties(TABLE_DEF));

    // (re) set the label provider, for one which can handle multiple
    // columns.
    nodeTreeView.getTreeViewer().setLabelProvider(
        new NodeEditorLabelProvider());

    // set a cell modifier
    nodeTreeView.getTreeViewer().setCellModifier(this);
    nodeTreeView.getTreeViewer().setSorter(new NodeWrapperTreeSorter());

    return baseComposite;
  }

  private void setupRelationPicker(Composite parent) {
    Composite region = new Composite(parent, SWT.NONE);
    region.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
    region.setLayout(new GridLayout(2, false));

    Label pickerLabel = RelationshipSetPickerControl.createPickerLabel(region);
    pickerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

    relationshipSetPicker = new RelationshipSetPickerControl(region);
    relationshipSetPicker.selectSet(DefaultRelationshipSet.SET);
    relationshipSetPicker.addChangeListener(this);
    relationshipSetPicker.setLayoutData(
        new GridData(SWT.FILL, SWT.CENTER, true, false));
  }

  /*
   * (non-Javadoc)
   * return true if the property can be edited.
   *
   * @see org.eclipse.jface.viewers.ICellModifier#canModify(java.lang.Object,
   *      java.lang.String)
   */
  public boolean canModify(Object element, String property) {
    return EditColTableDef.get(TABLE_DEF, property).isEditable();
  }

  /*
   * (non-Javadoc)
   * return the value to be edited for the given element and property.
   *
   * @see org.eclipse.jface.viewers.ICellModifier#getValue(java.lang.Object,
   *      java.lang.String)
   */
  @SuppressWarnings("unchecked")
  public Object getValue(Object element, String property) {
    if (element instanceof NodeWrapper) {
      NodeDisplayProperty p = getProp((NodeWrapper) element);
      if (property.equals(COL_VISIBLE)) {
        return p.isVisible();
      } else if (property.equals(COL_SELECTED)) {
        return p.isSelected();
      } else if (property.equals(COL_SIZE)) {
        return p.getSize().ordinal();
      } else if (property.equals(COL_COLOR)) {
        return p.getColor() == null ? "" : p.getColor().toString();
      }
    }
    return null;
  }

  /**
   * return the {@link NodeDisplayProperty} associated with the given element.
   *
   * @param element
   * @return the property associated with the given {@link NodeWrapper}.
   */
  @SuppressWarnings("unchecked")
  private NodeDisplayProperty getProp(NodeWrapper element) {
    return ((NodeWrapper<NodeDisplayProperty>) element).getContent();
  }

  /*
   * (non-Javadoc)
   * Change the element property to the given value.
   *
   * @see org.eclipse.jface.viewers.ICellModifier#modify(java.lang.Object,
   *      java.lang.String, java.lang.Object)
   */
  // suppressWarning: we cast an Object to NodeWrapper, the parameter type is
  // unchecked.
  @SuppressWarnings("unchecked")
  public void modify(Object element, String property, Object value) {
    if (!(element instanceof TreeItem)) {
      return;
    }
    Object o = ((TreeItem) element).getData();
    if (!(o instanceof NodeWrapper)) {
      return;
    }
    NodeWrapper<NodeDisplayProperty> prop =
        (NodeWrapper<NodeDisplayProperty>) o;
    NodeDisplayProperty p = prop.getContent();
    GraphNode node = prop.getNode();

//    System.out.println("" + p + " " + property + " - " + value);
    if (property.equals(COL_VISIBLE) && (value instanceof Boolean)) {
      p.setVisible((Boolean) value);
    } else if (property.equals(COL_SELECTED) && (value instanceof Boolean)) {
      p.setSelected((Boolean) value);
    } else if (property.equals(COL_SIZE) && (value instanceof Integer)) {
      p.setSize(Size.values()[(Integer) value]);
    } else if (property.equals(COL_COLOR) && (value instanceof String)) {
      Color newColor = StringUtils.stringToColor((String) value);
      p.setColor(newColor);
    }
    // notify the listeners about this change
    getEditor().setNodeProperty(node, p);
    // update the column / line we just modified
    nodeTreeView.getTreeViewer().update(o, new String[] {property});
  }

  /**
   * after a change of editor, reset the content of the tree.
   */
  protected void refresh() {
    // TODO(leeca): how can this tool be active and not have a valid editor?
    if (!hasEditor()) {
      return;
    }

    GraphData<NodeDisplayProperty> hierarchy = getEditorHierarchy();
    nodeTreeView.updateData(hierarchy);
  }

  private GraphData<NodeDisplayProperty> getEditorHierarchy() {
    RelationshipSet relSet = relationshipSetPicker.getSelection();
    DirectedRelationFinder relFinder = relSet;
    GraphData<NodeDisplayProperty> hierarchy =
        getEditor().getHierarchy(relFinder);
    return hierarchy;
  }

  @Override
  public NodeDisplayProperty getObject(GraphNode node) {
    return getEditor().getNodeProperty(node);
  }

  @Override
  public void editorClosed(ViewEditor viewEditor) {
    GraphData<NodeDisplayProperty> hierarchy = getEditorHierarchy();
    hierarchy.saveExpandState(
        nodeTreeView.getTreeViewer().getExpandedTreePaths());
    super.editorClosed(viewEditor);
  }

  @Override
  public void emptySelection() {
    refresh();
  }

  /**
   * Change the selection state of the given node to the new value, in the list
   * of nodes properties.
   * @param node the node to change.
   * @param value the selection value.
   */
  private void setSelectedState(GraphNode node, boolean value) {
    NodeDisplayProperty prop = getEditor().getNodeProperty(node);
    if (null != prop) {
      // set the property
      prop.setSelected(value);
      NodeWrapper<NodeDisplayProperty> nodeWrapper =
          nodeTreeView.getNodeWrapper(node);

      if (null != nodeWrapper) {
        // update the value in the table. this might be faster than updating
        // all the list at the end. because a selection generally doesn't
        // change a lot.
        nodeTreeView.getTreeViewer().update(nodeWrapper,
            new String[] { COL_SELECTED });
      }
    }
  }

  /**
   * Set the given set of nodes as selected in the list.
   * @param selection set of nodes
   */
  @Override
  public void updateSelectedAdd(GraphNode[] selection) {
    for (GraphNode node : selection) {
      setSelectedState(node, true);
    }
  }

  /**
   * Set the given set of nodes as unselected in the list.
   *
   * @param selection set of nodes
   * @see com.google.devtools.depan.eclipse.views.ViewSelectionListenerTool
   *      #updateSelectedRemove(com.google.devtools.depan.model.GraphNode[])
   */
  @Override
  public void updateSelectedRemove(GraphNode[] selection) {
    for (GraphNode node : selection) {
      setSelectedState(node, false);
    }
  }

  /**
   * Set the nodes in the given set as the only ones selected. Which means
   * that the previously selected are first unselected, then nodes in the
   * <code>selection</code> argument are added.
   */
  @Override
  public void updateSelectionTo(GraphNode[] selection) {
    for (GraphNode node : getEditor().getViewGraph().getNodes()) {
      NodeDisplayProperty nodeProps = getEditor().getNodeProperty(node);
      nodeProps.setSelected(false);
      NodeWrapper<NodeDisplayProperty> nodeWrapper =
          nodeTreeView.getNodeWrapper(node);
      if (null != nodeWrapper) {
        // update the value in the table. this might be faster than updating
        // all the list at the end. because a selection generally doesn't
        // change a lot.
        nodeTreeView.getTreeViewer().update(nodeWrapper,
            new String[] { COL_SELECTED });
      }
    }
    updateSelectedAdd(selection);
  }

  @Override
  public void selectedSetChanged(RelationshipSet set) {
    refresh();
  }
}
