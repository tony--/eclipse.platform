/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ant.internal.ui.editor.outline;

import org.eclipse.ant.internal.ui.AntUIImages;
import org.eclipse.ant.internal.ui.IAntUIConstants;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.custom.BusyIndicator;

/** 
 * An action which toggles filtering of properties from the Ant outline.
 */
public class FilterTopLevelAction extends Action {
	
	private AntEditorContentOutlinePage fPage;
	
	public FilterTopLevelAction(AntEditorContentOutlinePage page) {
		super(AntOutlineMessages.getString("FilterTopLevelAction.0")); //$NON-NLS-1$
		fPage = page;
		setImageDescriptor(AntUIImages.getImageDescriptor(IAntUIConstants.IMG_FILTER_TOP_LEVEL));
		setToolTipText(AntOutlineMessages.getString("FilterTopLevelAction.0")); //$NON-NLS-1$
		setChecked(fPage.filterTopLevel());
	}
	
	/**
	 * Toggles the filtering of top level tasks and types from the Ant outline
	 * @see org.eclipse.jface.action.IAction#run()
	 */
	public void run() {
		BusyIndicator.showWhile(fPage.getControl().getDisplay(), new Runnable() {
			public void run() {
				fPage.setFilterTopLevel(isChecked());
			}
		});
	}
}