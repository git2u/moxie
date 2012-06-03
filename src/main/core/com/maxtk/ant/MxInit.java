/*
 * Copyright 2012 James Moger
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
package com.maxtk.ant;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Path.PathElement;

import com.maxtk.Build;
import com.maxtk.Constants.Key;
import com.maxtk.Pom;
import com.maxtk.Scope;
import com.maxtk.utils.StringUtils;

public class MxInit extends MxTask {

	private String config;
	
	public void setConfig(String config) {
		this.config = config;
	}

	@Override
	public void execute() throws BuildException {
		Build build = getBuild();
		if (build != null) {
			// already initialized
			return;
		}
		try {
			// push all mx properties from ant into system 
			Map<String,String> antProperties = getProject().getProperties();
			for (Map.Entry<String, String> entry : antProperties.entrySet()) {
				if (entry.getKey().startsWith("mx.")) {
					System.setProperty(entry.getKey(), entry.getValue());
				}
			}
			File configFile;
			if (StringUtils.isEmpty(config)) {
				// default configuration
				configFile = new File("build.maxml");
			} else {
				// specified configuration
				configFile = new File(config);
			}
			
			// load any associated properties file (e.g. build.properties)
			File propsFile = new File(configFile.getAbsoluteFile().getParentFile(),
					configFile.getName().substring(0, configFile.getName().lastIndexOf('.')) + ".properties");
			if (propsFile.exists()) {
				Properties props = new Properties();
				FileInputStream is = new FileInputStream(propsFile);
				props.load(is);
				is.close();
				
				Project project = getProject();
				for (Map.Entry<Object, Object> entry : props.entrySet()) {
					project.setProperty(entry.getKey().toString(), entry.getValue().toString());
				}
				if (isVerbose()) {
					console.log("loaded " + propsFile);
				}
			}
			
			build = new Build(configFile);
			build.getPom().setAntProperties(antProperties);			

			// add a reference to the full build object
			addReference(Key.build, build, false);
			
			//setProperty("project.name", build.getPom().name);
			
			// output the build info
			build.describe();
			
			build.setup(isVerbose());
			
			console = build.console;
			if (isVerbose()) {
				build.console.separator();
				build.console.log(getProject().getProperty("ant.version"));
				build.console.log("Maxilla ant properties", getProject().getProperty("ant.version"));
			}

			Pom pom = build.getPom();
			
			if (isVerbose()) {
				build.console.separator();
				build.console.log("string properties");
			}
			
			setProjectProperty(Key.name, pom.name);
			setProjectProperty(Key.description, pom.description);
			setProjectProperty(Key.groupId, pom.groupId);
			setProjectProperty(Key.artifactId, pom.artifactId);
			setProjectProperty(Key.version, pom.version);
			setProjectProperty(Key.organization, pom.organization);
			setProjectProperty(Key.url, pom.url);

			setProperty(Key.reportsFolder, build.getReportsFolder().toString());
			setProperty(Key.targetFolder, build.getTargetFolder().toString());
			setProperty(Key.outputFolder, build.getOutputFolder(null).toString());
			setProperty(Key.compile_outputpath, build.getOutputFolder(Scope.compile).toString());
			setProperty(Key.test_outputpath, build.getOutputFolder(Scope.test).toString());

			if (isVerbose()) {
				build.console.separator();
				build.console.log("path references");
			}
			
			setSourcepath(Key.compile_sourcepath, build, Scope.compile);
			setSourcepath(Key.test_sourcepath, build, Scope.test);

			setClasspath(Key.compile_classpath, build, Scope.compile);
			setClasspath(Key.runtime_classpath, build, Scope.runtime);
			setClasspath(Key.test_classpath, build, Scope.test);
			setClasspath(Key.build_classpath, build, Scope.build);

			setDependencypath(Key.compile_dependencypath, build, Scope.compile);
			setDependencypath(Key.runtime_dependencypath, build, Scope.runtime);
			setDependencypath(Key.test_dependencypath, build, Scope.test);	
		} catch (Exception e) {
			throw new BuildException(e);
		}		
	}
	
	protected void setProjectProperty(Key prop, String value) {
		if (!StringUtils.isEmpty(value)) {
			getProject().setProperty(prop.projectId(), value);
			log(prop.projectId(), value, false);
		}
	}

	
	private void setSourcepath(Key key, Build build, Scope scope) {
		Set<File> folders = new LinkedHashSet<File>();
		folders.addAll(build.getSourceFolders(scope));
		folders.addAll(build.getSourceFolders(Scope.defaultScope));
		
		Path sources = new Path(getProject());
		for (File file : folders) {
			PathElement element = sources.createPathElement();
			element.setLocation(file);
		}
		addReference(key, sources, true);
	}
	
	private void setClasspath(Key key, Build build, Scope scope) {
		List<File> jars = build.getClasspath(scope);
		Path cp = new Path(getProject());
		// output folder
		PathElement of = cp.createPathElement();
		of.setLocation(build.getOutputFolder(scope));
		if (!scope.isDefault()) {
			of.setLocation(build.getOutputFolder(Scope.compile));
		}
		
		// add project dependencies 
		for (File folder : buildDependentProjectsClasspath(build)) {
			PathElement element = cp.createPathElement();
			element.setLocation(folder);
		}
		
		// jars
		for (File jar : jars) {
			PathElement element = cp.createPathElement();
			element.setLocation(jar);
		}

		addReference(key, cp, true);
	}
	
	private void setDependencypath(Key key, Build build, Scope scope) {
		List<File> jars = build.getClasspath(scope);
		Path cp = new Path(getProject());
		for (File jar : jars) {
			PathElement element = cp.createPathElement();
			element.setLocation(jar);
		}
		addReference(key, cp, true);
	}
	
	private List<File> buildDependentProjectsClasspath(Build build) {
		List<File> folders = new ArrayList<File>();
		List<Build> libraryProjects = build.getLinkedProjects();
		for (Build project : libraryProjects) {
			File outputFolder = project.getOutputFolder(Scope.compile);
			folders.add(outputFolder);
		}		
		return folders;
	}
}
