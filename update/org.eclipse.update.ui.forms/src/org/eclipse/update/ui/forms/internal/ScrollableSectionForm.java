/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.update.ui.forms.internal;

import org.eclipse.swt.graphics.*;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.*;

/**
 * This version of the section form adds scrolling
 * capability. However, scrolling can be disabled
 * using 'setScrollable' method. For this reason,
 * this class can be used instead of the SectionForm.
 */

public class ScrollableSectionForm extends SectionForm {
	private Composite container;
	private boolean verticalFit;
	private boolean scrollable = true;

	public ScrollableSectionForm() {
	}
	public Control createControl(Composite parent) {
		container = createParent(parent);
		Control formControl = super.createControl(container);
		if (container instanceof ScrolledComposite) {
			ScrolledComposite sc = (ScrolledComposite) container;
			sc.setContent(formControl);
		}
		GridData gd = new GridData(GridData.FILL_BOTH);
		formControl.setLayoutData(gd);
		container.setBackground(formControl.getBackground());
		return container;
	}
	protected Composite createParent(Composite parent) {
		Composite result = null;
		if (isScrollable()) {
			ScrolledComposite scomp =
				new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
			if (isVerticalFit()) {
				scomp.setExpandHorizontal(true);
				scomp.setExpandVertical(true);
			}
			initializeScrollBars(scomp);
			result = scomp;
		} else {
			result = new Composite(parent, SWT.NONE);
			GridLayout layout = new GridLayout();
			layout.marginHeight = 0;
			layout.marginWidth = 0;
			result.setLayout(layout);
		}
		result.setMenu(parent.getMenu());
		return result;
	}
	public boolean isScrollable() {
		return scrollable;
	}
	public boolean isVerticalFit() {
		return verticalFit;
	}
	public void setScrollable(boolean newScrollable) {
		scrollable = newScrollable;
	}

	public void setVerticalFit(boolean newVerticalFit) {
		verticalFit = newVerticalFit;
	}

	private void initializeScrollBars(ScrolledComposite scomp) {
		ScrollBar hbar = scomp.getHorizontalBar();
		if (hbar != null) {
			hbar.setIncrement(H_SCROLL_INCREMENT);
		}
		ScrollBar vbar = scomp.getVerticalBar();
		if (vbar != null) {
			vbar.setIncrement(V_SCROLL_INCREMENT);
		}
		updatePageIncrement(scomp);
	}

	public void update() {
		super.update();
		if (container instanceof ScrolledComposite) {
			updateScrolledComposite();
		} else {
			container.layout(true);
		}
	}
	public void updateScrollBars() {
		if (container instanceof ScrolledComposite) {
			updateScrolledComposite();
		}
	}
	public void updateScrolledComposite() {
		ScrolledComposite sc = (ScrolledComposite) container;
		Control formControl = getControl();
		Point newSize = formControl.computeSize(SWT.DEFAULT, SWT.DEFAULT);
		formControl.setSize(newSize);
		sc.setMinSize(newSize);
		updatePageIncrement(sc);
	}
}