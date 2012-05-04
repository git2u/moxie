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
package com.maxtk;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.maxtk.Constants.Key;
import com.maxtk.Dependency.Scope;
import com.maxtk.maxml.Maxml;
import com.maxtk.maxml.MaxmlException;
import com.maxtk.utils.FileUtils;
import com.maxtk.utils.StringUtils;

public class Config implements Serializable {

	private static final long serialVersionUID = 1L;

	Pom pom;
	
	List<String> projects;
	List<String> repositoryUrls;	
	
	File dependencyFolder;
	List<File> sourceFolders;
	File outputFolder;
	boolean configureEclipseClasspath;
	boolean debug;

	public static Config load(File file) throws IOException, MaxmlException {
		return new Config().parse(file);
	}

	public Config() {
		// default configuration
		sourceFolders = Arrays.asList(new File("src"));
		outputFolder = new File("bin");
		projects = new ArrayList<String>();
		repositoryUrls = new ArrayList<String>();		
		pom = new Pom();
		dependencyFolder = null;
	}

	@Override
	public String toString() {
		return "Config (" + pom + ")";
	}

	Config parse(File file) throws IOException, MaxmlException {
		if (!file.exists()) {
			throw new MaxmlException(MessageFormat.format("{0} does not exist!", file));			
		}
		
		String content = FileUtils.readContent(file, "\n").trim();
		Map<String, Object> map = Maxml.parse(content);

		// metadata
		pom.name = readString(map, Key.name, false);
		pom.version = readString(map, Key.version, false);
		pom.groupId = readString(map, Key.groupId, false);
		pom.artifactId = readString(map, Key.artifactId, false);
		pom.description = readString(map, Key.description, false);
		pom.url = readString(map, Key.url, false);
		pom.vendor = readString(map, Key.vendor, false);

		// build parameters
		configureEclipseClasspath = readBoolean(map, Key.configureEclipseClasspath, false);
		sourceFolders = readFiles(map, Key.sourceFolders, sourceFolders);
		outputFolder = readFile(map, Key.outputFolder, outputFolder);
		projects = readStrings(map, Key.projects, projects);
		dependencyFolder = readFile(map, Key.dependencyFolder, null);
		
		List<String> props = readStrings(map, Key.properties, new ArrayList<String>());
		for (String prop : props) {
			String [] values = prop.split(" ");
			pom.setProperty(values[0], values[1]);
		}
		pom.setProperty("project.groupId", pom.groupId);
		pom.setProperty("project.artifactId", pom.artifactId);
		pom.setProperty("project.version", pom.version);	
		
		repositoryUrls = readStrings(map, Key.dependencySources, repositoryUrls);
		parseDependencies(map, Key.dependencies);		
		return this;
	}

	void parseDependencies(Map<String, Object> map, Key key) {
		if (map.containsKey(key.name())) {
			List<?> values = (List<?>) map.get(key.name());
			Scope scope;
			for (Object definition : values) {
				if (definition instanceof String) {
					String def = definition.toString();
					if (def.startsWith("compile")) {
						// compile-time dependency
						scope = Scope.compile;
						def = def.substring("compile".length()).trim();
					} else if (def.startsWith("provided")) {
						// provided dependency
						scope = Scope.provided;
						def = def.substring("provided".length()).trim();
					} else if (def.startsWith("runtime")) {
						// runtime dependency
						scope = Scope.runtime;
						def = def.substring("runtime".length()).trim();
					} else if (def.startsWith("test")) {
						// test dependency
						scope = Scope.test;
						def = def.substring("test".length()).trim();
					} else if (def.startsWith("system")) {
						// system dependency
						scope = Scope.system;
						def = def.substring("system".length()).trim();
					} else {
						// default to compile-time dependency
						scope = Scope.compile;
					}
					
					def = StringUtils.stripQuotes(def);										
					Dependency dep = new Dependency(def);					
					pom.addDependency(dep, scope);
				} else {
					throw new RuntimeException("Illegal dependency " + definition);
				}
			}
		}		
	}

	String readString(Map<String, Object> map, Key key, boolean required) {
		Object o = map.get(key.name());
		if (o != null && !StringUtils.isEmpty(o.toString())) {
			return o.toString();
		} else {
			if (required) {
				keyError(key);
			}
			return null;
		}
	}

	String readString(Map<String, Object> map, Key key, String defaultValue) {
		Object o = map.get(key.name());
		if (o != null && !StringUtils.isEmpty(o.toString())) {
			return o.toString();
		} else {
			return defaultValue;
		}
	}

	@SuppressWarnings("unchecked")
	List<String> readStrings(Map<String, Object> map, Key key, List<String> defaultValue) {
		if (map.containsKey(key.name())) {
			List<String> strings = new ArrayList<String>();
			Object o = map.get(key.name());
			if (o instanceof List) {
				List<String> values = (List<String>) o;
				strings.addAll(values);
			} else if (o instanceof String) {
				String list = o.toString();
				for (String value : StringUtils.breakCSV(list)) {
					if (!StringUtils.isEmpty(value)) {
						strings.add(value);
					}
				}
			}
			if (strings.size() == 0) {
				keyError(key);
			} else {
				return strings;
			}
		}
		return defaultValue;
	}

	boolean readBoolean(Map<String, Object> map, Key key, boolean defaultValue) {
		if (map.containsKey(key.name())) {
			Object o = map.get(key.name());
			if (StringUtils.isEmpty(o.toString())) {
				keyError(key);
			} else {
				return Boolean.parseBoolean(o.toString());
			}
		}
		return defaultValue;
	}

	File readFile(Map<String, Object> map, Key key, File defaultValue) {
		if (map.containsKey(key.name())) {
			Object o = map.get(key.name());
			if (!(o instanceof String)) {
				keyError(key);
			} else {
				String dir = o.toString();
				if (StringUtils.isEmpty(dir)) {
					keyError(key);
				} else {
					return new File(dir);
				}
			}
		}
		return defaultValue;
	}

	@SuppressWarnings("unchecked")
	List<File> readFiles(Map<String, Object> map, Key key, List<File> defaultValue) {
		if (map.containsKey(key.name())) {
			Object o = map.get(key.name());
			if (o instanceof List) {
				// list
				List<File> values = new ArrayList<File>();
				List<String> list = (List<String>) o;
				for (String dir : list) {
					if (!StringUtils.isEmpty(dir)) {
						values.add(new File(dir));
					}
				}
				if (values.size() == 0) {
					keyError(key);
				} else {
					return values;
				}
			} else {
				// string definition
				List<File> values = new ArrayList<File>();
				String list = o.toString();
				for (String value : StringUtils.breakCSV(list)) {
					if (!StringUtils.isEmpty(value)) {
						values.add(new File(value));
					}
				}
				if (values.size() == 0) {
					keyError(key);
				} else {
					return values;
				}
			}
		}
		return defaultValue;
	}

	void keyError(Key key) {
		System.err.println(MessageFormat.format("{0} is improperly specified, using default", key.name()));
	}

	public String getName() {
		return pom.name;
	}

	public String getDescription() {
		return pom.description;
	}

	public String getVersion() {
		return pom.version;
	}

	public String getUrl() {
		return pom.url;
	}

	public String getVendor() {
		return pom.vendor;
	}

	public String getGroupId() {
		return pom.groupId;
	}
	
	public String getArtifactId() {
		return pom.artifactId;
	}
	
	public List<File> getSourceFolders() {
		return sourceFolders;
	}

	public File getOutputFolder() {
		return outputFolder;
	}

	public List<String> getProjects() {
		return projects;
	}

	public boolean configureEclipseClasspath() {
		return configureEclipseClasspath;
	}
}
