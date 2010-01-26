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

package com.google.devtools.depan.eclipse.utils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.depan.eclipse.plugins.SourcePlugin;
import com.google.devtools.depan.eclipse.plugins.SourcePluginRegistry;
import com.google.devtools.depan.eclipse.wizards.NewRelationshipSetWizard;
import com.google.devtools.depan.filters.PathExpression;
import com.google.devtools.depan.filters.PathMatcher;
import com.google.devtools.depan.filters.PathMatcherTerm;
import com.google.devtools.depan.graph.api.DirectedRelation;
import com.google.devtools.depan.graph.api.DirectedRelationFinder;
import com.google.devtools.depan.graph.api.Relation;
import com.google.devtools.depan.graph.basic.BasicDirectedRelation;
import com.google.devtools.depan.graph.basic.MultipleDirectedRelationFinder;
import com.google.devtools.depan.graph.basic.ReversedDirectedRelationFinder;
import com.google.devtools.depan.model.RelationshipSet;
import com.google.devtools.depan.model.RelationshipSetAdapter;

import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.CheckboxCellEditor;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A GUI tool to display a list of relationships, with a selector for forward
 * and backward directions. This is a editable table (directions are editable,
 * content and names are not).
 *
 * To use it, call {@link #getControl(Composite)} to retrieve the widget.
 *
 * @author ycoppel@google.com (Yohann Coppel)
 *
 */
public class RelationshipPicker
    implements ModificationListener<DirectedRelation, Boolean>,
    RelationshipSelectorListener, ViewerObjectToString {

  /**
   * The actual table.
   */
  private TableViewer relationPicker = null;

  /**
   * Content provider for the table.
   */
  private TableContentProvider<DirectedRelation>
      relationPickerContent = null;

  /**
   * A mapping from a Relation to a DirectedRelation. This makes easy to
   * retrieve a {@link DirectedRelation} in the middle of the
   * {@link #relationPicker}.
   */
  private Map<Relation, DirectedRelation> contentMap;

  /**
   * Listeners for changes in the model.
   */
  private Collection<ModificationListener<DirectedRelation, Boolean>>
      listeners = Lists.newArrayList();

  /**
   * The instance set. Can be modified, but can't be saved.
   */
  private RelationshipSet instanceSet = new RelationshipSetAdapter("");

  /**
   * the currently selected {@link RelationshipSet}.
   */
  private RelationshipSet selectedSet = null;

  /**
   * The quick selector on top of this widget applying a selection to the list
   * of relationship.
   */
  private RelationshipSetPickerControl relationshipSetPicker = null;

  /**
   * A shell necessary to open dialogs.
   */
  private Shell shell = null;

  /**
   * The Path Matcher Model used for cumulative filtering.
   */
  private PathMatcher pathMatcherModel;

  /**
   * return a {@link Control} for this widget, containing every useful buttons,
   * labels, table... necessary to use this component.
   *
   * @param parent the parent.
   * @return a {@link Control} containing this widget.
   */
  public Control getControl(Composite parent) {
    this.shell = parent.getShell();

    // component
    Composite panel = new Composite(parent, SWT.BORDER);
    panel.setLayout(new GridLayout());

    // components inside the panel
    setupRelationPicker(panel);

    Button reverseAll = new Button(panel, SWT.PUSH);
    reverseAll.setText("Reverse all lines");
    reverseAll.setLayoutData(
        new GridData(SWT.FILL, SWT.FILL, false, false));
    
    relationPicker = new TableViewer(
        panel, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL);

    setupRelationToggles(panel);

    // initialize the table
    Table relationTable = relationPicker.getTable();
    relationTable.setHeaderVisible(true);
    relationTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    EditColTableDef.setupTable(
        RelationshipPickerHelper.TABLE_DEF, relationTable);

    CellEditor[] cellEditors = new CellEditor[3];
    cellEditors[0] = null;
    cellEditors[1] = new CheckboxCellEditor(relationTable);
    cellEditors[2] = new CheckboxCellEditor(relationTable);

    // cell content
    relationPicker.setCellEditors(cellEditors);
    relationPicker.setColumnProperties(EditColTableDef.getProperties(
        RelationshipPickerHelper.TABLE_DEF));
    SelectionEditorTableEditor tableLabelProvider =
        new SelectionEditorTableEditor(relationPicker, this);
    relationPicker.setLabelProvider(tableLabelProvider);
    relationPicker.setCellModifier(tableLabelProvider);

    // content provider
    relationPickerContent =
        new TableContentProvider<DirectedRelation>();
    relationPickerContent.initViewer(relationPicker);
    relationPicker.setSorter(new AlphabeticSorter(this));

    // options actions
    reverseAll.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        reverseSelection();
      }
    });

    fill();
    fillSets();
    return panel;
  }

  private void setupRelationPicker(Composite parent) {
    Composite region = new Composite(parent, SWT.NONE);
    region.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
    region.setLayout(new GridLayout(3, false));

    Label pickerLabel = RelationshipSetPickerControl.createPickerLabel(region);
    pickerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

    relationshipSetPicker = new RelationshipSetPickerControl(region);
    relationshipSetPicker.selectSet(DefaultRelationshipSet.SET);
    relationshipSetPicker.addChangeListener(this);
    relationshipSetPicker.setLayoutData(
        new GridData(SWT.FILL, SWT.CENTER, true, false));

    Button save = new Button(region, SWT.PUSH);
    save.setText("Save selection as");
    save.setLayoutData(
      new GridData(SWT.FILL, SWT.CENTER, false, false));

    save.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        saveAsAction();
      }
    });
  }

  private void setupRelationToggles(Composite parent) {
    Label optionsLabel = new Label(parent, SWT.NONE);
    optionsLabel.setText("For selected lines:");
    optionsLabel.setLayoutData(
        new GridData(SWT.LEFT, SWT.FILL, false, false));

    Composite toggles = new Composite(parent, SWT.NONE);
    toggles.setLayout(new GridLayout(8, false));
    toggles.setLayoutData(
        new GridData(SWT.FILL, SWT.FILL, true, false));

    Label forward = new Label(toggles, SWT.NONE);
    forward.setText("Forward");
    forward.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

    Button forwardAll = new Button(toggles, SWT.PUSH);
    forwardAll.setImage(Resources.IMAGE_ON);
    forwardAll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

    Button forwardNone = new Button(toggles, SWT.PUSH);
    forwardNone.setImage(Resources.IMAGE_OFF);
    forwardNone.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

    Button forwardReverse = new Button(toggles, SWT.PUSH);
    forwardReverse.setText("Reverse");
    forwardReverse.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

    Label backward = new Label(toggles, SWT.NONE);
    backward.setText("Backward");
    backward.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

    Button backwardAll = new Button(toggles, SWT.PUSH);
    backwardAll.setImage(Resources.IMAGE_ON);
    backwardAll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

    Button backwardNone = new Button(toggles, SWT.PUSH);
    backwardNone.setImage(Resources.IMAGE_OFF);
    backwardNone.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

    Button backwardReverse = new Button(toggles, SWT.PUSH);
    backwardReverse.setText("Reverse");
    backwardReverse.setLayoutData(
        new GridData(SWT.FILL, SWT.FILL, true, false));

    // actions
    forwardAll.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        forwardSelectAll(true);
      }
    });

    forwardNone.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        forwardSelectAll(false);
      }
    });

    forwardReverse.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        forwardReverseSelection();
      }
    });

    backwardAll.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        backwardSelectAll(true);
      }
    });

    backwardNone.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        backwardSelectAll(false);
      }
    });

    backwardReverse.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        backwardReverseSelection();
      }
    });
  }

  /**
   * Reverse the forward setting for all selected lines.
   */
  // suppressWarnings: .iterator in IStructuredSelection is not parameterized
  @SuppressWarnings("unchecked")
  protected void forwardReverseSelection() {
    Iterator selectedLines =
      ((IStructuredSelection) relationPicker.getSelection()).iterator();
    while (selectedLines.hasNext()) {
      Object line = selectedLines.next();
      if (line instanceof DirectedRelation) {
        DirectedRelation rel = (DirectedRelation) line;
        setForward(rel.getRelation(), !rel.matchForward(), true);
      }
    }
    copyToAndSelectInstanceSet();
  }

  /**
   * Reverse the backward setting for all selected lines.
   */
  // suppressWarnings: .iterator in IStructuredSelection is not parameterized
  @SuppressWarnings("unchecked")
  protected void backwardReverseSelection() {
    Iterator selectedLines =
      ((IStructuredSelection) relationPicker.getSelection()).iterator();
    while (selectedLines.hasNext()) {
      Object line = selectedLines.next();
      if (line instanceof DirectedRelation) {
        DirectedRelation rel = (DirectedRelation) line;
        setBackward(rel.getRelation(), !rel.matchBackward(), true);
      }
    }
    copyToAndSelectInstanceSet();
  }

  /**
   * Reverse the selection: select all directions that are not enabled, while
   * deselecting each direction that were initially selected.
   */
  protected void reverseSelection() {
    selectFinder(new ReversedDirectedRelationFinder(getSelectedRelations()));
  }

  /**
   * Fill the list with {@link Relation}s.
   */
  private void fill() {
    contentMap = new HashMap<Relation, DirectedRelation>();
    for (SourcePlugin p : SourcePluginRegistry.getInstances()) {
      for (Relation r : p.getRelations()) {
        DirectedRelation directedRelation = new BasicDirectedRelation(r);
        // add to the content provider
        relationPickerContent.add(directedRelation);
        // and to the map for fast retrieval
        contentMap.put(r, directedRelation);
      }
    }
    relationPicker.refresh(false);
  }

  /**
   * Fill the quick selection list for existing named sets of relationships.
   * Prefixes means:
   * - * is a built-in relationship set
   * - // is the temporary relationship set
   * - + are user-defined sets.
   *
   * We look for sets in every opened project in the current workspace.
   */
  private void fillSets() {
    Map<RelationshipSet, String> sets = Maps.newHashMap();
    sets.put(instanceSet, "Temporary set");

    relationshipSetPicker.addSets(sets);
    // select the temporary set
    selectRelationshipSet(instanceSet);
    relationshipSetPicker.selectSet(instanceSet);
  }

  /**
   * (un)Select the forward direction for each selected relations types.
   *
   * @param select true to select, false to unselect.
   */
  // supressWarning because Iterator should be parameterized, but the .iterator
  // in IStructuredSelection is not.
  @SuppressWarnings("unchecked")
  protected void forwardSelectAll(boolean select) {
    Iterator selectedLines =
      ((IStructuredSelection) relationPicker.getSelection()).iterator();
    while (selectedLines.hasNext()) {
      Object line = selectedLines.next();
      if (line instanceof DirectedRelation) {
        setForward(((DirectedRelation) line).getRelation(), select, true);
      }
    }
    copyToAndSelectInstanceSet();
  }

  /**
   * (un)Select the backward direction for each selected relations types.
   *
   * @param select true to select, false to unselect.
   */
  // supressWarning because Iterator should be parameterized, but the .iterator
  // in IStructuredSelection is not.
  @SuppressWarnings("unchecked")
  protected void backwardSelectAll(boolean select) {
    Iterator selectedLines =
      ((IStructuredSelection) relationPicker.getSelection()).iterator();
    while (selectedLines.hasNext()) {
      Object line = selectedLines.next();
      if (line instanceof DirectedRelation) {
        setBackward(((DirectedRelation) line).getRelation(), select, true);
      }
    }
    copyToAndSelectInstanceSet();
  }

  /**
   * @return a {@link MultipleDirectedRelationFinder} representing the selected
   *         relationships and their direction.
   */
  public MultipleDirectedRelationFinder getRelationShips() {
    // get a set of relations
    MultipleDirectedRelationFinder finder =
        new MultipleDirectedRelationFinder();

    for (DirectedRelation relation : relationPickerContent.getObjects()) {
      finder.addRelation(relation.getRelation(),
          relation.matchForward(), relation.matchBackward());
    }
    return finder;
  }

  /**
   * deselect all the relations in both forward and backward direction.
   * @param notify true if we want to notify listeners that the object changed.
   */
  private void unselectAll(boolean notify) {
    for (DirectedRelation relation : relationPickerContent.getObjects()) {
      boolean toUpdate = relation.matchBackward() || relation.matchForward();
      relation.setMatchBackward(false);
      relation.setMatchForward(false);
      if (toUpdate) {
        relationPicker.update(relation, RelationshipPickerHelper.CHANGING_COLS);
        if (notify) {
          notifyListeners(
              relation, RelationshipPickerHelper.COL_FORWARD, false);
          notifyListeners(
              relation, RelationshipPickerHelper.COL_BACKWARD, false);
        }
      }
    }
  }

  /**
   * (un)select the forward direction for the {@link Relation} relation.
   * @param relation the relation
   * @param on true to select, false to unselect
   * @param notify true to notify the listeners of the changes
   */
  private void setForward(Relation relation, boolean on, boolean notify) {
    DirectedRelation directedRelation = contentMap.get(relation);
    if (null == directedRelation) {
      return;
    }
    if (on != directedRelation.matchForward()) {
      directedRelation.setMatchForward(on);
      // update the table
      relationPicker.update(directedRelation,
          new String[] {RelationshipPickerHelper.COL_FORWARD});
      if (notify) {
        // notify the listeners that this column has changed
        notifyListeners(contentMap.get(relation),
            RelationshipPickerHelper.COL_FORWARD, on);
      }
    }
  }

  /**
   * (un)select the backward direction for the {@link Relation} relation.
   * @param relation the relation
   * @param on true to select, false to unselect
   * @param notify true to notify the listeners of the changes
   */
  private void setBackward(Relation relation, boolean on, boolean notify) {
    DirectedRelation directedRelation = contentMap.get(relation);
    if (null == directedRelation) {
      return;
    }
    if (on != directedRelation.matchBackward()) {
      directedRelation.setMatchBackward(on);
      // update the table
      relationPicker.update(directedRelation,
          new String[] {RelationshipPickerHelper.COL_BACKWARD});
      if (notify) {
        // notify listeners
        notifyListeners(contentMap.get(relation),
            RelationshipPickerHelper.COL_BACKWARD, on);
      }
    }
  }

  /**
   * register a {@link ModificationListener}.
   * @param listener the new listener
   */
  public void registerListener(
      ModificationListener<DirectedRelation, Boolean> listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  /**
   * Unregister the listener.
   * @param listener to un-register
   */
  public void unRegisterListener(
      ModificationListener<DirectedRelation, Boolean> listener) {
    if (listeners.contains(listener)) {
      listeners.remove(listener);
    }
  }

  /**
   * Select the given {@link RelationshipSet}. Its definition will be reflected
   * on the view: only its enabled directions will be selected.
   * @param set the new set.
   */
  public void selectRelationshipSet(RelationshipSet set) {
    unselectAll(false);
    for (Relation relation : set.getBackwardRelations()) {
      setBackward(relation, true, false);
    }
    for (Relation relation : set.getForwardRelations()) {
      setForward(relation, true, false);
    }
    this.selectedSet = set;
  }

  /*
   * This method doesn't notify of the changes, unless if the selected set is
   * set to instanceSet.
   *
   * (non-Javadoc)
   *
   * @see com.google.devtools.depan.eclipse.utils.RelationshipSelectorListener
   *      #copySelectionFromSet(
   *          com.google.devtools.depan.relationship.RelationshipSet)
   */
  public void selectedSetChanged(RelationshipSet set) {
    this.selectedSet = set;
    unselectAll(set != instanceSet);
    for (Relation relation : set.getBackwardRelations()) {
      setBackward(relation, true, set != instanceSet);
    }
    for (Relation relation : set.getForwardRelations()) {
      setForward(relation, true, set != instanceSet);
    }
  }

  /**
   * Select the directions described by the {@link DirectedRelationFinder}
   *
   * @param finder finder describing each direction.
   */
  public void selectFinder(DirectedRelationFinder finder) {
    for (SourcePlugin p : SourcePluginRegistry.getInstances()) {
      for (Relation relation : p.getRelations()) {
        setForward(relation, finder.matchForward(relation), true);
        setBackward(relation, finder.matchBackward(relation), true);
      }
    }
    copyToAndSelectInstanceSet();
  }

  /*
   * (non-Javadoc)
   *
   * @see com.google.devtools.depan.eclipse.utils.ModificationListener
   *      #modify(java.lang.Object, java.lang.String, java.lang.Object)
   */
  public void modify(DirectedRelation element, String property, Boolean value) {
    notifyListeners(element, property, value);

    // after the change, if we are currently editing the instance set,
    // we modify it to get the changes back if we leave/come back to the
    // temporary set.
    if (selectedSet == instanceSet) {
      if (property.equals(RelationshipPickerHelper.COL_FORWARD)) {
        instanceSet.setMatchForward(element.getRelation(), value);
      } else if (property.equals(RelationshipPickerHelper.COL_BACKWARD)) {
        instanceSet.setMatchBackward(element.getRelation(), value);
      }
    } else {
      copyToAndSelectInstanceSet();
    }
  }

  /**
   * Notify the listener when a change is made in the selection.
   *
   * @param element the element which changed
   * @param property the property involved
   * @param value the new value
   */
  private void notifyListeners(
      DirectedRelation element, String property, Boolean value) {
    for (ModificationListener<DirectedRelation, Boolean> listener : listeners) {
      listener.modify(element, property, value);
    }
  }

  /**
   * Copy the selected directions to the instanceSet, then select it. This
   * happens when an existing set is selected, and we modify it. So the new
   * modified set is copied to the temporary set, that we can use freely (even
   * save it under a new name...)
   */
  private void copyToAndSelectInstanceSet() {
    for (DirectedRelation relation : contentMap.values()) {
      instanceSet.setMatchForward(relation.getRelation(),
          relation.matchForward());
      instanceSet.setMatchBackward(relation.getRelation(),
          relation.matchBackward());
    }
    this.selectedSet = instanceSet;
    relationshipSetPicker.selectSet(instanceSet);
  }

  /*
   * (non-Javadoc)
   *
   * @see com.google.devtools.depan.eclipse.utils.ViewerObjectToString
   *      #getString(java.lang.Object)
   */
  public String getString(Object object) {
    if (!(object instanceof DirectedRelation)) {
      return object.toString();
    }
    DirectedRelation relation = (DirectedRelation) object;
    return relation.getRelation().toString();
  }

  /**
   * Because this GUI class is a maintain informations about directions for
   * relations, it can be viewed as a DirectedRelationFider. This method return
   * a DirectedRelationFinder representing this class at a precise moment.
   *
   * @return a representation of this selector as a DirectedRelationFinder.
   */
  public DirectedRelationFinder getSelectedRelations() {
    MultipleDirectedRelationFinder finder =
        new MultipleDirectedRelationFinder();
    for (DirectedRelation relation : contentMap.values()) {
      finder.addOrReplaceRelation(relation.getRelation(),
          relation.matchForward(), relation.matchBackward());
    }
    return finder;
  }

  /*
   * (non-Javadoc)
   *
   * @see com.google.devtools.depan.eclipse.utils.RelationshipSelectorListener
   *      #saveAsAction()
   */
  public void saveAsAction() {
    NewRelationshipSetWizard wizard =
        new NewRelationshipSetWizard(getSelectedRelations());
    WizardDialog dialog = new WizardDialog(shell, wizard);
    dialog.open();
  }

  /**
   * Creates a Path Matcher Model that will be used in conjunction to this tool.
   *
   * @param isRecursive Shows whether the selected relations should be applied
   * recursively.
   */
  public void createPathMatcherModel(boolean isRecursive) {
    String setName = getSelectedRelationshipSet().getName();
    PathExpression pathExpressionModel = new PathExpression();
    RelationshipSetAdapter setAdapterFromPicker =
        new RelationshipSetAdapter(setName, getRelationShips(),
            SourcePluginRegistry.getRelations());
    pathExpressionModel.addPathMatcher(
        new PathMatcherTerm(setAdapterFromPicker, isRecursive, false));
    pathMatcherModel = pathExpressionModel;
  }

  /**
   * Accessor for the <code>PathMatcher</code>.
   *
   * @return The <code>PathMatcher</code> model associated with this tool.
   */
  public PathMatcher getPathMatcherModel() {
    return pathMatcherModel;
  }

  /**
   * Returns the <code>RelationshipSet</code> that contains the selected
   * relations in this <code>RelationshipPicker</code>.
   *
   * @return Set of relations selected in this picker. Returns an empty object
   * if a valid {@link RelationshipSetSelector} object is not found.
   */
  public RelationshipSet getSelectedRelationshipSet() {
    if (relationshipSetPicker == null) {
      return RelationshipSetAdapter.EMTPY;
    }
    return relationshipSetPicker.getSelection();
  }
}
