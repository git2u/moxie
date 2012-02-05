/*
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2000, 2001, 2002, 2003 Jesse Stockall.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowlegement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "The Jakarta Project", "Tomcat", and "Apache Software
 *    Foundation" must not be used to endorse or promote products derived
 *    from this software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache"
 *    nor may "Apache" appear in their names without prior written
 *    permission of the Apache Group.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 */
package com.maxtk.ant;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;


/**
 * Driver class for the <genjar> task.
 * <p>
 * 
 * This class is instantiated when Ant encounters the &lt;genjar&gt; element.
 * 
 * @author Original Code: <a href="mailto:jake@riggshill.com">John W. Kohler</a>
 * @author Jesse Stockall
 * @version $Revision: 1.11 $ $Date: 2003/03/06 01:22:00 $
 */
public class GenJar extends Task {
	
	protected List jarSpecs = new ArrayList(32);

	private List libraries = new ArrayList(8);

	protected Mft mft = new Mft();

	protected Path classpath = null;

	private ClassFilter classFilter = null;

	private File destFile = null;

	private File destDir = null;

	private PathResolver[] resolvers = null;

	private Set resolved = new HashSet();

	private Logger logger = null;

	/** Constructor for the GenJar object */
	public GenJar() {
		setTaskName("maxjar");
	}

	/**
	 * main execute for genjar
	 * <ol>
	 * <li> setup logger
	 * <li> ensure classpath is setup (with any additions from sub-elements
	 * <li> initialize file resolvers
	 * <li> initialize the manifest
	 * <li> resolve resource file paths resolve class file paths generate
	 * dependancy graphs for class files and resolve those paths check for
	 * duplicates
	 * <li> generate manifest entries for all candidate files
	 * <li> build jar
	 * </ol>
	 * 
	 * 
	 * @throws BuildException
	 *             Oops!
	 */
	public void execute() throws BuildException {
		long start = System.currentTimeMillis();

		logger = new Logger(getProject());
		if (classFilter == null) {
			classFilter = new ClassFilter(logger);
		}
		
		if ((destFile == null) && (destDir == null)) {
			throw new BuildException(
					"maxjar: Either a destfile or destdir attribute is required",
					getLocation());
		}
		// if (mft == null)
		// {
		// throw new BuildException("No manifest specified", getLocation());
		// }
		mft.setBaseDir(getProject().getBaseDir());

		if (destFile != null) {
			log("Generating jar: " + destFile);
		}

		if (destDir != null) {
			log("Generating class structure in: " + destDir);
		}

		//
		// set up the classpath & resolvers - file/jar/zip
		//
		try {
			if (classpath == null) {
				classpath = new Path(getProject());
			}
			if (!classpath.isReference()) {
				//
				// don't like this - I could find no way to build
				// the classpath dynamically from the LibrarySpec
				// objects - the path just disappeared (has something
				// with actual execution order I think) - so here's the
				// brute force approach - if the library is of jar type
				// then it'll return a Path object that we can insert
				//
				for (Iterator it = libraries.iterator(); it.hasNext();) {
					Path p = ((LibrarySpec) it.next()).getPathElement();
					if (p != null) {
						classpath.addExisting(p);
					}
				}

				//
				// add the system path now - AFTER all other paths are
				// specified
				//
				classpath.addExisting(Path.systemClasspath);
			}
			logger.verbose("Initializing Path Resolvers");
			logger.verbose("Classpath:" + classpath);
			initPathResolvers();
		} catch (IOException ioe) {
			throw new BuildException("Unable to process classpath: " + ioe,
					getLocation());
		}
		//
		// prep the manifest
		//
		mft.execute(logger);
		//
		// run over all the resource and clsss specifications
		// given in the project file
		// resources are resolved to full path names while
		// class specifications are exploded to dependancy
		// graphs - when done, getJarEntries() returns a list
		// of all entries generated by this JarSpec
		//
		List entries = new LinkedList();

		for (Iterator it = jarSpecs.iterator(); it.hasNext();) {
			JarSpec js = (JarSpec) it.next();
			try {
				js.resolve(this);
			} catch (FileNotFoundException ioe) {
					throw new BuildException("Unable to resolve: " + js.getName() + "\nFileNotFound=" + ioe.getMessage(),
							ioe, getLocation());
			} catch (IOException ioe) {
					throw new BuildException("Unable to resolve: " + js.getName() + "\nMSG=" + ioe.getMessage(),
						ioe, getLocation());
			}
			//
			// before adding a new jarspec - see if it already exists
			// first entry added to jar always wins
			//
			List jarEntries = js.getJarEntries();
			for (Iterator iter = jarEntries.iterator(); iter.hasNext();) {
				JarEntrySpec spec = (JarEntrySpec) iter.next();
				if (!entries.contains(spec)) {
					entries.add(spec);
				} else {
					logger.verbose("Duplicate (ignored): " + spec.getJarName());
				}
			}
		}
		//
		// we have all the entries we're gonna jar - the manifest
		// must be fully built prior to jar generation, so run over
		// each entry and and add it to the manifest
		//
		for (Iterator it = entries.iterator(); it.hasNext();) {
			JarEntrySpec jes = (JarEntrySpec) it.next();
			if (jes.getSourceFile() == null) {
				try {
					InputStream is = resolveEntry(jes);
					if (is != null) {
						is.close();
					}
				} catch (IOException ioe) {
					throw new BuildException(
							"Error while generating manifest entry for: "
									+ jes.toString(), ioe, getLocation());
				}
			}
//			mft.addEntry(jes.getJarName(), jes.getAttributes());
		}

		if (destFile != null) {
			JarOutputStream jout = null;
			InputStream is = null;
			try {
				jout = new JarOutputStream(new FileOutputStream(destFile), mft
						.getManifest());

				for (Iterator it = entries.iterator(); it.hasNext();) {
					JarEntrySpec jes = (JarEntrySpec) it.next();
					JarEntry entry = new JarEntry(jes.getJarName());
					is = resolveEntry(jes);

					if (is == null) {
						logger
								.error("Unable to locate previously resolved resource");
						logger.error("       Jar Name:" + jes.getJarName());
						logger.error(" Resoved Source:" + jes.getSourceFile());
						try {
							if (jout != null) {
								jout.close();
							}
						} catch (IOException ioe) {
						}
						throw new BuildException("Jar component not found: "
								+ jes.getJarName(), getLocation());
					}
					jout.putNextEntry(entry);
					byte[] buff = new byte[4096]; // stream copy buffer
					int len;
					while ((len = is.read(buff, 0, buff.length)) != -1) {
						jout.write(buff, 0, len);
					}
					jout.closeEntry();
					is.close();

					logger.verbose("Added: " + jes.getJarName());
				}
			} catch (IOException ioe) {
				throw new BuildException("Unable to create jar: "
						+ destFile.getName(), ioe, getLocation());
			} finally {
				try {
					if (is != null) {
						is.close();
					}
				} catch (IOException ioe) {
				}
				try {
					if (jout != null) {
						jout.close();
					}
				} catch (IOException ioe) {
				}
			}
			log("Jar Generated (" + (System.currentTimeMillis() - start)
					+ " ms)");
		}

		// Destdir has been specified, so try to generate the dependencies on
		// disk
		if (destDir != null) {
			if (!destDir.exists()) {
				throw new BuildException("Destination directory: \'" + destDir
						+ "\' does not exist", getLocation());
			}

			if (!destDir.isDirectory()) {
				throw new BuildException("Destination directory: \'" + destDir
						+ "\' is not a valid directory", getLocation());
			}

			FileOutputStream fileout = null;
			InputStream is = null;

			try {
				for (Iterator it = entries.iterator(); it.hasNext();) {
					JarEntrySpec jes = (JarEntrySpec) it.next();
					String classname = jes.getJarName();
					int index = classname.lastIndexOf("/");
					String path = "";
					if (index > 0) {
						path = classname.substring(0, index);
					}
					classname = classname.substring(index + 1);

					File filepath = new File(destDir, path);
					if (!filepath.exists()) {
						if (!filepath.mkdirs()) {
							throw new BuildException(
									"Unable to create directory: "
											+ filepath.getName(), getLocation());
						}
					}
					File classfile = new File(filepath, classname);
					logger.debug("Writing: " + classfile.getAbsolutePath());
					fileout = new FileOutputStream(classfile);
					is = resolveEntry(jes);

					if (is == null) {
						logger
								.error("Unable to locate previously resolved resource");
						logger.error("       Jar Name:" + jes.getJarName());
						logger.error(" Resoved Source:" + jes.getSourceFile());
						try {
							if (fileout != null) {
								fileout.close();
							}
						} catch (IOException ioe) {
						}
						throw new BuildException("File not found \'"
								+ jes.getJarName() + "\'", getLocation());
					}
					byte[] buff = new byte[4096]; // stream copy buffer
					int len;
					while ((len = is.read(buff, 0, buff.length)) != -1) {
						fileout.write(buff, 0, len);
					}
					fileout.close();
					is.close();

					logger.verbose("Wrote: " + classfile.getName());
				}
			} catch (IOException ioe) {
				throw new BuildException("Unable to write classes to: "
						+ destDir.getName(), ioe, getLocation());
			} finally {
				try {
					if (is != null) {
						is.close();
					}
				} catch (IOException ioe) {
				}
				try {
					if (fileout != null) {
						fileout.close();
					}
				} catch (IOException ioe) {
				}
			}

			log("Class Structure Generated ("
					+ (System.currentTimeMillis() - start) + " ms)");
		}

		// Close all the resolvers
		for (int i = 0, length = resolvers.length; i < length; i++) {
			try {
				resolvers[i].close();
			} catch (IOException ioe) {
			}
		}
	}

	/**
	 * Sets the classpath attribute.
	 * 
	 * @param s
	 *            The new classpath.
	 */
	public void setClasspath(Path s) {
		createClasspath().append(s);
	}

	/**
	 * Builds the classpath.
	 * 
	 * @return A <path>
	 * 
	 * element.
	 */
	public Path createClasspath() {
		if (classpath == null) {
			classpath = new Path(getProject());
		}
		return classpath;
	}

	/**
	 * Sets the Classpathref attribute.
	 * 
	 * @param r
	 *            The new classpathRef.
	 */
	public void setClasspathRef(Reference r) {
		createClasspath().setRefid(r);
	}

	/**
	 * Builds a <class> element.
	 * 
	 * @return A <class> element.
	 */
	public ClassSpec createClass() {
		ClassSpec cs = new ClassSpec(getProject());
		jarSpecs.add(cs);
		return cs;
	}
	
	/**
	 * Builds a manifest element.
	 * 
	 * @return A <manifest> element.
	 */
	public Object createManifest() {
		mft.setBaseDir(getProject().getBaseDir());
		return mft;
	}

	/**
	 * Builds a resource element.
	 * 
	 * @return A <resource> element.
	 */
	public Resource createResource() {
		Resource rsc = new Resource(getProject());
		jarSpecs.add(rsc);
		return rsc;
	}

	/**
	 * Builds a classfilter element.
	 * 
	 * @return A <classfilter> element.
	 */
	public ClassFilter createClassfilter() {
		if (classFilter == null) {
			classFilter = new ClassFilter(new Logger(getProject()));
		}
		return classFilter;
	}

	/**
	 * Builds a library element.
	 * 
	 * @return A <library> element.
	 */
	public LibrarySpec createLibrary() {
		LibrarySpec lspec = new LibrarySpec(getProject().getBaseDir(),
				new Path(getProject()));
		jarSpecs.add(lspec);
		libraries.add(lspec);
		return lspec;
	}


	/**
	 * Sets the name of the jar file to be created.
	 * 
	 * @param destFile
	 *            The new destfile value
	 */
	public void setDestfile(File destFile) {
		this.destFile = destFile;
	}

	/**
	 * Sets the name of the directory where the classes will be copied.
	 * 
	 * @param path
	 *            The directory name.
	 */
	public void setDestdir(Path path) {
		destDir = getProject().resolveFile(path.toString());
	}

	//
	// TODO: path resolution needs to move to its own class
	//

	/**
	 * Iterate through the classpath and create an array of all the
	 * <code>PathResolver</code>s
	 * 
	 * @throws IOException
	 *             Description of the Exception
	 */
	private void initPathResolvers() throws IOException {
		List l = new ArrayList(32);
		String[] pc = classpath.list();

		for (int i = 0; i < pc.length; ++i) {
			File f = new File(pc[i]);
			if (!f.exists()) {
				continue;
			}

			if (f.isDirectory()) {
				l.add(new FileResolver(f, logger));
			} else if (f.getName().toLowerCase().endsWith(".jar")) {
				l.add(new JarResolver(f, logger));
			} else if (f.getName().toLowerCase().endsWith(".zip")) {
				l.add(new ZipResolver(f, logger));
			} else {
				throw new BuildException(f.getName()
						+ " is not a valid classpath component", getLocation());
			}
		}
		resolvers = (PathResolver[]) l.toArray(new PathResolver[0]);
	}

	/**
	 * Description of the Method
	 * 
	 * @param spec
	 *            Description of the Parameter
	 * @return Description of the Return Value
	 * @throws IOException
	 *             Description of the Exception
	 */
	InputStream resolveEntry(JarEntrySpec spec) throws IOException {
		InputStream is = null;
		for (int i = 0; i < resolvers.length; ++i) {
			is = resolvers[i].resolve(spec);
			if (is != null) {
				return is;
			}
		}
		return null;
	}

	/**
	 * Resolves a partial file name against the classpath elements
	 * 
	 * @param cname
	 *            Description of the Parameter
	 * @return An InputStream open on the named file or null
	 * @throws IOException
	 *             Description of the Exception
	 */
	InputStream resolveEntry(String cname) throws IOException {
		InputStream is = null;

		for (int i = 0; i < resolvers.length; ++i) {
			is = resolvers[i].resolve(cname);
			if (is != null) {
				return is;
			}
		}
		return null;
	}

	// =====================================================================
	// TODO: class dependancy determination needs to move to either its own
	// class or to ClassSpec
	// =====================================================================

	/**
	 * Generates a list of all classes upon which the list of classes depend.
	 * 
	 * @param entries
	 *            List of <code>JarEntrySpec</code>s used as a list of class
	 *            names from which to start.
	 * @exception IOException
	 *                If there's an error reading a class file
	 */
	void generateDependancies(List entries) throws IOException {
		List dependants = new LinkedList();

		for (Iterator it = entries.iterator(); it.hasNext();) {
			JarEntrySpec js = (JarEntrySpec) it.next();
			generateClassDependancies(js.getJarName(), dependants);
		}

		for (Iterator it = dependants.iterator(); it.hasNext();) {
			entries.add(new JarEntrySpec(it.next().toString(), null));
		}
	}

	/**
	 * Generates a list of classes upon which the named class is dependant.
	 * 
	 * @param classes
	 *            A List into which the class names are placed
	 * @param classFileName
	 *            Description of the Parameter
	 * @throws IOException
	 *             Description of the Exception
	 */
	void generateClassDependancies(String classFileName, List classes)
			throws IOException {
		if (!resolved.contains(classFileName)) {
			resolved.add(classFileName);
			InputStream is = resolveEntry(classFileName);
			if (is == null) {
				throw new FileNotFoundException(classFileName);
			}

			List referenced = ClassUtil.getDependancies(is);

			for (Iterator it = referenced.iterator(); it.hasNext();) {
				String cname = it.next().toString() + ".class";

				if (!classFilter.include(cname) || resolved.contains(cname)) {
					continue;
				}

				classes.add(cname);
				generateClassDependancies(cname, classes);
			}
			is.close();
		}
	}
}
