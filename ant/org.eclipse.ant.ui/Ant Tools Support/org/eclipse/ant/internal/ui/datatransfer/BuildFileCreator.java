/*******************************************************************************
 * Copyright (c) 2004, 2006 Richard Hoefter and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Richard Hoefter (richard.hoefter@web.de) - initial API and implementation, bugs 95297, 97051 
 *     IBM Corporation - nlsing and incorporating into Eclipse, bug 108276
 *     Nikolay Metchev (N.Metchev@teamphone.com) - bug 108276
 *******************************************************************************/

package org.eclipse.ant.internal.ui.datatransfer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Commandline;
import org.eclipse.ant.internal.ui.AntUIPlugin;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Creates build.xml file.
 */
public class BuildFileCreator
{
    protected static final String WARNING = " WARNING: Eclipse autogenerated file. " + ExportUtil.NEWLINE +//$NON-NLS-1$
                                            "              Any modifications will be overwritten." + ExportUtil.NEWLINE + //$NON-NLS-1$
                                            "              Please edit build-user.xml instead." + ExportUtil.NEWLINE; //$NON-NLS-1$
    
    private static final String USER_TARGET = "<target name=\"help\">" + ExportUtil.NEWLINE + //$NON-NLS-1$
                                              "    <echo message=\"Please run: $ ant -v -projecthelp\"/>" + ExportUtil.NEWLINE + //$NON-NLS-1$
                                              "</target>"; //$NON-NLS-1$
    
    private Document doc;
    private Element root;
    private String projectName;
    private String projectRoot;
    
    private BuildFileCreator() {}

    /**
     * Constructor. Please prefer {@link #createBuildFiles(IJavaProject)} if
     * you do not want call the various createXXX() methods yourself.
     */
    public BuildFileCreator(IJavaProject project) throws ParserConfigurationException
    {
        this.projectName = project.getProject().getName();
        this.projectRoot = ExportUtil.getProjectRoot(project);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        this.doc = dbf.newDocumentBuilder().newDocument();
    }
     
    /**
     * Create build.xml file for given project. If project depends on other projects, their build files
     * are created as well.
     */
    public static void create(IJavaProject javaProject) throws JavaModelException, ParserConfigurationException,
        TransformerConfigurationException, TransformerException, IOException, CoreException
    {
        BuildFileCreator instance = new BuildFileCreator();
        instance.createBuildFiles(javaProject);
    }
    
    /**
     * Create build.xml file for given project. If project depends on other projects, their build files
     * are created as well.
     */
    public void createBuildFiles(IJavaProject javaProject) throws JavaModelException, ParserConfigurationException,
        TransformerConfigurationException, TransformerException, IOException, CoreException
    {
        Set allSubProjects = ExportUtil.getClasspathProjectsRecursive(javaProject);
        allSubProjects.add(javaProject);
        for (Iterator iter = allSubProjects.iterator(); iter.hasNext();)
        {
            Map variable2valueMap = new TreeMap();
            IJavaProject mainProject = (IJavaProject) iter.next();
            this.projectName = mainProject.getProject().getName();
            this.projectRoot = ExportUtil.getProjectRoot(mainProject);

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            this.doc = dbf.newDocumentBuilder().newDocument();
          
            createRoot();

            Set subProjects = ExportUtil.getClasspathProjects(mainProject);

            // add classpath
            Element classpathElement = createClasspath(mainProject, subProjects, variable2valueMap);
            // subclasspaths
            addSubClasspaths(variable2valueMap, subProjects);

            EclipseClasspath classpath = new EclipseClasspath(mainProject);
            List classDirsUnique = classpath.getClassDirsUnique();

            createInit(classDirsUnique);   
            createClean(classDirsUnique);
            
            createBuild(subProjects, classpath.class2sourcesMap,
                        classpath.class2includesMap, classpath.class2excludesMap);
            
            createRun(variable2valueMap);
            
            createProperty(variable2valueMap, classpathElement);
          
            String xml = ExportUtil.addEntity(doc, "buildfile", "./build-user.xml"); //$NON-NLS-1$ //$NON-NLS-2$

            // write build file
            BufferedWriter out = new BufferedWriter(new FileWriter(projectRoot + "/build.xml")); //$NON-NLS-1$
            out.write(xml.toString());
            out.close();
            
            // create user build file
            File userBuildFile = new File(projectRoot + "/build-user.xml"); //$NON-NLS-1$
            if (! userBuildFile.exists())
            {
                out = new BufferedWriter(new FileWriter(userBuildFile));
                out.write(USER_TARGET);
                out.close();                
            }
            
            // sync file system
            mainProject.getProject().getFile("build.xml").refreshLocal(IResource.DEPTH_ZERO, null); //$NON-NLS-1$
            mainProject.getProject().getFile("build-user.xml").refreshLocal(IResource.DEPTH_ZERO, null); //$NON-NLS-1$
        }
    }

    /**
     * Add property tag.
     * @param variable2valueMap    properties to add
     * @param classpathElement     insert property tag before this tag
     */
    public void createProperty(Map variable2valueMap, Element classpathElement)
    {
        // <property name="x" value="y"/>
        for (Iterator iterator = variable2valueMap.keySet().iterator(); iterator.hasNext();)
        {
            String key = (String) iterator.next();
            String value = (String) variable2valueMap.get(key);
            Element prop = doc.createElement("property"); //$NON-NLS-1$
            prop.setAttribute("name", key); //$NON-NLS-1$
            prop.setAttribute("value", value); //$NON-NLS-1$
            root.insertBefore(prop, classpathElement);
        }
    }

    /**
     * Create project tag.
     */
    public void createRoot()
    {
        // <project name="hello" default="build" basedir=".">
        this.root = doc.createElement("project"); //$NON-NLS-1$
        root.setAttribute("name" , projectName); //$NON-NLS-1$
        root.setAttribute("default" , "build"); //$NON-NLS-1$ //$NON-NLS-2$
        root.setAttribute("basedir" , "."); //$NON-NLS-1$ //$NON-NLS-2$
        doc.appendChild(root);
        
        // <!-- warning -->
        Comment comment = doc.createComment(WARNING);
        doc.insertBefore(comment, root);
    }

    /**
     * Create classpath tag.
     * @return created path tag
     */
    public Element createClasspath(IJavaProject mainProject, Set subProjects, Map variable2valueMap) throws JavaModelException
    {
        Element element;
        if (subProjects.size() == 0)
        {
            // add project classpath
            element = createClasspath("project.classpath", mainProject, null, variable2valueMap); //$NON-NLS-1$
        }
        else
        {
            // add project classpath (contains only references to other classpaths)
            element = createMainClasspath(subProjects);
            // add mainproject classpath (without subproject items)
            createClasspath("mainproject.classpath", mainProject, null, variable2valueMap); //$NON-NLS-1$
        }
        return element;
    }

    /**
     * Create classpath tag.
     * @param pathId                 path id
     * @param newProjectRoot         project root as prefix for classDirs,
     *                               e.g. ${project.location}
     * @return created path tag
     */
    public Element createClasspath(String pathId, IJavaProject project, String newProjectRoot, Map variable2valueMap) throws JavaModelException
    {
        // <path id="hello.classpath">
        //     <pathelement location="${hello.location}/classes"/>
        //     <pathelement location="${hello.location}/x.jar"/>
        // </path>
        Element element = doc.createElement("path"); //$NON-NLS-1$
        element.setAttribute("id", pathId); //$NON-NLS-1$
        EclipseClasspath classpath = new EclipseClasspath(project, newProjectRoot);
        variable2valueMap.putAll(classpath.variable2valueMap);
        for (Iterator iterator = classpath.removeDuplicates(classpath.rawClassPathEntries).iterator(); iterator.hasNext();)
        {
            String entry = (String) iterator.next();
            entry= getRelativePath(entry, projectRoot);
            Element pathElement = doc.createElement("pathelement"); //$NON-NLS-1$
            pathElement.setAttribute("location", entry); //$NON-NLS-1$
            element.appendChild(pathElement);
        }
        root.appendChild(element);
        return element;
    }

    /**
     * Create main classpath tag which only references subproject classpaths.
     * @param subProjects    subprojects to reference
     * @return created path tag
     */
    public Element createMainClasspath(Set subProjects)
    {
        // <path id="project.classpath">
        //    <path refid="mainproject.classpath"/>
        //    <path refid="subproject.classpath"/>
        // </path>
        Element element = doc.createElement("path"); //$NON-NLS-1$
        element.setAttribute("id", "project.classpath"); //$NON-NLS-1$ //$NON-NLS-2$

        Element pathElement = doc.createElement("path"); //$NON-NLS-1$
        pathElement.setAttribute("refid", "mainproject.classpath");  //$NON-NLS-1$//$NON-NLS-2$
        element.appendChild(pathElement);
        
        for (Iterator iter = subProjects.iterator(); iter.hasNext();)
        {
            IJavaProject subProject = (IJavaProject) iter.next();
            String refid = subProject.getProject().getName() + ".classpath"; //$NON-NLS-1$
            pathElement = doc.createElement("path"); //$NON-NLS-1$
            pathElement.setAttribute("refid", refid); //$NON-NLS-1$
            element.appendChild(pathElement);
        }
        root.appendChild(element);
        return element;
    }

    /**
     * Add subclasspaths.
     * @param variable2valueMap    adds subproject properties to this map
     * @param subProjects          subprojects
     */
    private void addSubClasspaths(Map variable2valueMap, Set subProjects) throws JavaModelException
    {
        for (Iterator iterator = subProjects.iterator(); iterator.hasNext();)
        {
            IJavaProject subProject = (IJavaProject) iterator.next();
            String location = subProject.getProject().getName() + ".location"; //$NON-NLS-1$
                           
            // add subproject properties to variable2valueMap
            String subProjectRoot = ExportUtil.getProjectRoot(subProject);
            String relativePath = getRelativePath(subProjectRoot, projectRoot);
            variable2valueMap.put(location, relativePath);
            EclipseClasspath classpath = new EclipseClasspath(subProject, "${" + location + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // add subproject properties to variable2valueMap
            variable2valueMap.putAll(classpath.variable2valueMap);
            // add subproject classpaths
            createClasspath(subProject.getProject().getName() + ".classpath", subProject, "${" + location + "}", variable2valueMap);              //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * Returns a path which is equivalent to the given location relative to the
     * specified base path.
     */
    public static String getRelativePath(String otherLocation, String basePath) {
        IPath location= new Path(otherLocation);
        IPath base= new Path(basePath);
        if ((location.getDevice() != null && !location.getDevice().equalsIgnoreCase(base.getDevice())) || !location.isAbsolute()) {
            return otherLocation;
        }
        int baseCount = base.segmentCount();
        int count = base.matchingFirstSegments(location);
        String temp = ""; //$NON-NLS-1$
        for (int j = 0; j < baseCount - count; j++) {
            temp += "../"; //$NON-NLS-1$
        }
        String relative= new Path(temp).append(location.removeFirstSegments(count)).toOSString();
        if (relative.length() == 0) {
            relative=  "."; //$NON-NLS-1$
        }
        
        return relative;
    }
      
    /**
     * Create init target.
     * @param classDirs    classes directories to create
     */
    public void createInit(List classDirs)
    {
        // <target name="init">
        //     <mkdir dir="classes"/>
        // </target>
        Element element = doc.createElement("target"); //$NON-NLS-1$
        element.setAttribute("name", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Iterator iterator = classDirs.iterator(); iterator.hasNext();)
        {
            String classDir = (String) iterator.next();
            if (! classDir.equals(".")) //$NON-NLS-1$
            {
                Element pathElement = doc.createElement("mkdir"); //$NON-NLS-1$
                pathElement.setAttribute("dir", classDir); //$NON-NLS-1$
                element.appendChild(pathElement);
            }
        }
        root.appendChild(element);
    }

    /**
     * Create clean target.
     * @param classDirs    classes directories to delete
     */
    public void createClean(List classDirs)
    {
        // <target name="clean">
        //     <delete dir="classes"/>
        // </target>
        Element element = doc.createElement("target"); //$NON-NLS-1$
        element.setAttribute("name", "clean"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Iterator iterator = classDirs.iterator(); iterator.hasNext();)
        {
            String classDir = (String) iterator.next();
            if (! classDir.equals(".")) //$NON-NLS-1$
            {
                Element deleteElement = doc.createElement("delete"); //$NON-NLS-1$
                deleteElement.setAttribute("dir", classDir); //$NON-NLS-1$
                element.appendChild(deleteElement);
            }
        }
        root.appendChild(element);
    }

    /**
     * Create build target.
     * @param subProjects          subprojects on which the mainproject depends on
     * @param class2sourcesMap     maps classes directories of mainproject to source directories
     * @param class2includesMap    maps classes directories of mainproject to inclusion filters
     * @param class2excludesMap    maps classes directories of mainproject to exclusion filters
     */
    public void createBuild(Set subProjects, Map class2sourcesMap, Map class2includesMap, Map class2excludesMap)
    {
        // <target name="build" depends="init">
        //     <ant antfile="${hello.location}/build.xml" inheritAll="false"/>
        //     <echo message="${ant.project.name}: ${ant.file}"/> 
        //     <javac destdir="classes">
        //         <src path="src"/>
        //         <include name=""/>
        //         <exclude name=""/>
        //         <classpath refid="project.classpath"/>
        //     </javac>    
        // </target>
        Element element = doc.createElement("target"); //$NON-NLS-1$
        element.setAttribute("name", "build"); //$NON-NLS-1$ //$NON-NLS-2$
        element.setAttribute("depends", "init"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Iterator iterator = subProjects.iterator(); iterator.hasNext();)
        {
            IJavaProject subProject = (IJavaProject) iterator.next();
            Element antElement = doc.createElement("ant"); //$NON-NLS-1$
            antElement.setAttribute("antfile", "${" + subProject.getProject().getName() + ".location}/build.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            antElement.setAttribute("inheritAll", "false");  //$NON-NLS-1$//$NON-NLS-2$
            element.appendChild(antElement);           
        }
        Element echoElement = doc.createElement("echo"); //$NON-NLS-1$
        echoElement.setAttribute("message", "${ant.project.name}: ${ant.file}"); //$NON-NLS-1$ //$NON-NLS-2$
        element.appendChild(echoElement);           
        for (Iterator iterator = class2sourcesMap.keySet().iterator(); iterator.hasNext();)
        {
            String classDir = (String) iterator.next();
            Set sources = (Set) class2sourcesMap.get(classDir);           
            Set inclusions = (Set) class2includesMap.get(classDir);
            Set exclusions = (Set) class2excludesMap.get(classDir);
            Element javacElement = doc.createElement("javac"); //$NON-NLS-1$
            javacElement.setAttribute("destdir", classDir); //$NON-NLS-1$
            for (Iterator iterator1 = sources.iterator(); iterator1.hasNext();)
            {
                String src = (String) iterator1.next();
                Element srcElement = doc.createElement("src"); //$NON-NLS-1$
                srcElement.setAttribute("path", src); //$NON-NLS-1$
                javacElement.appendChild(srcElement);
            }           
            for (Iterator iterator1 = inclusions.iterator(); iterator1.hasNext();)
            {
                String inclusion = (String) iterator1.next();
                Element includeElement = doc.createElement("include"); //$NON-NLS-1$
                includeElement.setAttribute("name", inclusion); //$NON-NLS-1$
                javacElement.appendChild(includeElement);
            }           
            for (Iterator iterator1 = exclusions.iterator(); iterator1.hasNext();)
            {
                String exclusion = (String) iterator1.next();
                Element excludeElement = doc.createElement("exclude"); //$NON-NLS-1$
                excludeElement.setAttribute("name", exclusion); //$NON-NLS-1$
                javacElement.appendChild(excludeElement);
            }           
            Element classpathRefElement = doc.createElement("classpath"); //$NON-NLS-1$
            classpathRefElement.setAttribute("refid", "project.classpath"); //$NON-NLS-1$ //$NON-NLS-2$
            javacElement.appendChild(classpathRefElement);
            element.appendChild(javacElement);
        }
        root.appendChild(element);
    }

    /**
     * Add variable/value for Eclipse variable. If given string is no variable, nothing is added.
     * 
     * @param variable2valueMap   property map to add variable/value
     * @param s                   String which may contain Eclipse variables, e.g. ${project_name}
     */
    private void addVariable(Map variable2valueMap, String s)
    {
        if (s == null || s.equals("")) //$NON-NLS-1$
        {
            return;
        }
        Pattern pattern = Pattern.compile("\\$\\{.*?\\}"); // ${var} //$NON-NLS-1$
        Matcher matcher = pattern.matcher(s);
        while (matcher.find())
        {           
            String variable = matcher.group();
            String value;
            try
            {
                value = VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(variable);    
            }
            catch (CoreException e)
            {
                // cannot resolve variable
                value = variable;
            }
            File file = new File(value);
            if (file.exists())
            {
               value = getRelativePath(file.getAbsolutePath(), projectRoot);
            }
            variable2valueMap.put(ExportUtil.removePrefixAndSuffix(variable, "${", "}"), value); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Add run targets.
     * @param variable2valueMap    adds Eclipse variables to this map,
     *                             if run configuration makes use of this feature
     */
    public void createRun(Map variable2valueMap) throws CoreException
    {
        // <target name="run">
        //     <java fork="yes" classname="class" failonerror="true" dir="." newenvironment="true">
        //         <env key="a" value="b"/>
        //         <jvmarg value="-Dx=y"/>
        //         <arg value="arg"/>
        //         <classpath refid="project.classpath"/>
        //     </java>
        // </target>
        ILaunchConfiguration[] confs = DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations();
        for (int i = 0; i < confs.length; i++)
        {
            ILaunchConfiguration conf = confs[i];
            if (!projectName.equals(conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, ""))) //$NON-NLS-1$
            {
                continue;
            }
                    
            if (conf.getType().getIdentifier().equals(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION))
            {
                addJavaApplication(variable2valueMap, conf);
            }
            else if (conf.getType().getIdentifier().equals(IJavaLaunchConfigurationConstants.ID_JAVA_APPLET))
            {
                addApplet(variable2valueMap, conf);
            }
            else if (conf.getType().getIdentifier().equals("org.eclipse.jdt.junit.launchconfig" /*JUnitLaunchConfiguration.ID_JUNIT_APPLICATION*/)) //$NON-NLS-1$
            {                    
                addJUnit(variable2valueMap, conf);
            }           
        }
    }

    /**
     * Convert java application launch configuration to ant target and add it to a document.
     * @param variable2valueMap    adds Eclipse variables to this map,
     *                             if run configuration makes use of this feature
     * @param conf                 Java application launch configuration
     */
    public void addJavaApplication(Map variable2valueMap, ILaunchConfiguration conf) throws CoreException
    {
        Element element = doc.createElement("target"); //$NON-NLS-1$
        element.setAttribute("name", conf.getName()); //$NON-NLS-1$
        Element javaElement = doc.createElement("java"); //$NON-NLS-1$
        javaElement.setAttribute("fork", "yes"); //$NON-NLS-1$ //$NON-NLS-2$
        javaElement.setAttribute("classname", conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "")); //$NON-NLS-1$ //$NON-NLS-2$
        javaElement.setAttribute("failonerror", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        String dir = conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY, ""); //$NON-NLS-1$
        addVariable(variable2valueMap, dir);                
        if (!dir.equals("")) //$NON-NLS-1$
        {
            javaElement.setAttribute("dir", dir); //$NON-NLS-1$
        }
        if (!conf.getAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true))
        {
            javaElement.setAttribute("newenvironment", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Map props = conf.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, new TreeMap());
        addElements(props, doc, javaElement, "env", "key", "value"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addElements(conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, ""), doc, javaElement, "jvmarg", "value", variable2valueMap); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addElements(conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, ""), doc, javaElement, "arg", "value", variable2valueMap); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        element.appendChild(javaElement);
        Element classpathRefElement = doc.createElement("classpath"); //$NON-NLS-1$
        classpathRefElement.setAttribute("refid", "project.classpath"); //$NON-NLS-1$ //$NON-NLS-2$
        javaElement.appendChild(classpathRefElement);
        root.appendChild(element);
    }

    /**
     * Convert applet launch configuration to Ant target and add it to a document. 
     * @param variable2valueMap    adds Eclipse variables to this map,
     *                             if run configuration makes use of this feature
     * @param conf                 applet configuration
     */
    public void addApplet(Map variable2valueMap, ILaunchConfiguration conf) throws CoreException
    {
        String dir = conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY, ""); //$NON-NLS-1$
        if (dir.equals("")) //$NON-NLS-1$
        {
            dir = projectRoot;
        }
        addVariable(variable2valueMap, dir);
        String value;
        try
        {
            value = VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(dir);    
        }
        catch (CoreException e)
        {
            // cannot resolve variable
            value = null;
        }

        String htmlfile = ((value != null) ? value : dir) + File.separator + conf.getName() + ".html"; //$NON-NLS-1$
        // TODO NOTE: Silently overwrites html file
        AppletUtil.buildHTMLFile(conf, htmlfile);
        Element element = doc.createElement("target"); //$NON-NLS-1$
        element.setAttribute("name", conf.getName()); //$NON-NLS-1$
        Element javaElement = doc.createElement("java"); //$NON-NLS-1$
        javaElement.setAttribute("fork", "yes");  //$NON-NLS-1$//$NON-NLS-2$
        javaElement.setAttribute("classname", conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_APPLET_APPLETVIEWER_CLASS, "sun.applet.AppletViewer")); //$NON-NLS-1$ //$NON-NLS-2$
        javaElement.setAttribute("failonerror", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        if (value != null)
        {
            javaElement.setAttribute("dir", dir); //$NON-NLS-1$
        }
        addElements(conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, ""), doc, javaElement, "jvmarg", "value", variable2valueMap);   //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
        addElements(conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, ""), doc, javaElement, "arg", "value", variable2valueMap);   //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
        addElements(conf.getName() + ".html", doc, javaElement, "arg", "value", variable2valueMap); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        element.appendChild(javaElement);
        Element classpathRefElement = doc.createElement("classpath"); //$NON-NLS-1$
        classpathRefElement.setAttribute("refid", "project.classpath");  //$NON-NLS-1$//$NON-NLS-2$
        javaElement.appendChild(classpathRefElement);
        root.appendChild(element);
    }
    
    /**
     * Convert JUnit launch configuration to JUnit task and add it to a document. 
     * @param variable2valueMap    adds Eclipse variables to this map,
     *                             if run configuration makes use of this feature
     * @param conf                 applet configuration
     */
    public void addJUnit(Map variable2valueMap, ILaunchConfiguration conf) throws CoreException
    {
        // <target name="runtest">
        //     <junit fork="yes" printsummary="withOutAndErr">
        //         <formatter type="plain"/>
        //         <test name="testclass"/>
        //         <env key="a" value="b"/>
        //         <jvmarg value="-Dx=y"/>
        //         <classpath refid="project.classpath"/>
        //     </junit>
        // </target>
        String testClass = conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, ""); //$NON-NLS-1$
        Element element;
        element = doc.createElement("target"); //$NON-NLS-1$
        element.setAttribute("name", conf.getName()); //$NON-NLS-1$
        Element junitElement = doc.createElement("junit"); //$NON-NLS-1$
        junitElement.setAttribute("fork", "yes"); //$NON-NLS-1$ //$NON-NLS-2$
        junitElement.setAttribute("printsummary", "withOutAndErr"); //$NON-NLS-1$ //$NON-NLS-2$
        String dir = conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY, ""); //$NON-NLS-1$
        addVariable(variable2valueMap, dir);                
        if (!dir.equals("")) //$NON-NLS-1$
        {
            junitElement.setAttribute("dir", dir); //$NON-NLS-1$
        }
        if (!conf.getAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true))
        {
            junitElement.setAttribute("newenvironment", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Element formatterElement = doc.createElement("formatter"); //$NON-NLS-1$
        formatterElement.setAttribute("type", "plain");  //$NON-NLS-1$//$NON-NLS-2$
        junitElement.appendChild(formatterElement);
        if (!testClass.equals("")) //$NON-NLS-1$
        {
            // Case 1: Single JUnit class
            Element testElement = doc.createElement("test"); //$NON-NLS-1$
            testElement.setAttribute("name", testClass); //$NON-NLS-1$
            junitElement.appendChild(testElement);                       
        }
        else
        {
            // Case 2: Run all tests in project, package or source folder
            String container = conf.getAttribute("org.eclipse.jdt.junit.CONTAINER" /*JUnitBaseLaunchConfiguration.LAUNCH_CONTAINER_ATTR*/, ""); //$NON-NLS-1$ //$NON-NLS-2$
            IType[] types = ExportUtil.findTestsInContainer(container);
            for (int i = 0; i < types.length; i++)
            {
                IType type = types[i];
                Element testElement = doc.createElement("test"); //$NON-NLS-1$
                testElement.setAttribute("name", type.getFullyQualifiedName()); //$NON-NLS-1$
                junitElement.appendChild(testElement);                       
            }
        }
        Map props = conf.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, new TreeMap());
        addElements(props, doc, junitElement, "env", "key", "value"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        addElements(conf.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, ""), doc, junitElement, "jvmarg", "value", variable2valueMap); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        element.appendChild(junitElement);
        Element classpathRefElement = doc.createElement("classpath"); //$NON-NLS-1$
        classpathRefElement.setAttribute("refid", "project.classpath");  //$NON-NLS-1$//$NON-NLS-2$
        junitElement.appendChild(classpathRefElement);
        root.appendChild(element);
    }

    /**
     * Create child nodes from <code>cmdLine</code> and add them to <code>element</code> which is part of
     * <code>doc</code>.
     * 
     * @param cmdLineArgs          command line arguments, separated with spaces or within double quotes, may also contain Eclipse variables 
     * @param doc                  XML document
     * @param element              node to add children to
     * @param elementName          name of new child node
     * @param attributeName        name of attribute for <code>values</code>
     * @param variable2valueMap    adds Eclipse variables to this map,
     *                             if command line makes use of this feature
     */
    private void addElements(String cmdLineArgs, Document xmlDoc, Element element, String elementName,
                                    String attributeName, Map variable2valueMap) throws CoreException
    {
        // need to add dummyexecutable to make use of class Commandline
        try {
            Commandline commandline = new Commandline("dummyexecutable " + cmdLineArgs); //$NON-NLS-1$
            String[] args = commandline.getArguments();
            for (int i = 0; i < args.length; i++)
            {
                String arg = args[i];
                addVariable(variable2valueMap, arg);
                Element itemElement = xmlDoc.createElement(elementName);
                itemElement.setAttribute(attributeName, arg);
                element.appendChild(itemElement);            
            }
        } catch (BuildException be) {
            throw new CoreException(new Status(IStatus.ERROR, AntUIPlugin.PI_ANTUI, 0, DataTransferMessages.BuildFileCreator_0, be));
        }
    }
    
    /**
     * Create child nodes from string map and add them to <code>element</code> which is part of
     * <code>doc</code>.
     * 
     * @param map                   key/value string pairs
     * @param doc                   XML document
     * @param element               node to add children to
     * @param elementName           name of new child node
     * @param keyAttributeName      name of key attribute
     * @param valueAttributeName    name of value attribute
     */
    private static void addElements(Map map, Document doc, Element element, String elementName,
                                    String keyAttributeName, String valueAttributeName)
    {
        for (Iterator iter = map.keySet().iterator(); iter.hasNext();)
        {
            String key = (String) iter.next();
            String value = (String) map.get(key);
            Element itemElement = doc.createElement(elementName);
            itemElement.setAttribute(keyAttributeName, key);
            itemElement.setAttribute(valueAttributeName, value);
            element.appendChild(itemElement);            
        }
    }
}