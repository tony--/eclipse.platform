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

package org.eclipse.ant.internal.ui.launchConfigurations;

import java.util.Map;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.IProcessFactory;
import org.eclipse.debug.core.model.IProcess;

public class RemoteAntProcessFactory implements IProcessFactory {

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.IProcessFactory#newProcess(org.eclipse.debug.core.ILaunch, java.lang.Process, java.lang.String, java.util.Map)
	 */
	public IProcess newProcess(ILaunch launch, Process process, String label, Map attributes) {
		attributes.put(IProcess.ATTR_PROCESS_TYPE, IAntLaunchConfigurationConstants.ID_ANT_PROCESS_TYPE);
		return new RemoteAntRuntimeProcess(launch, process, label, attributes);
	}
}
