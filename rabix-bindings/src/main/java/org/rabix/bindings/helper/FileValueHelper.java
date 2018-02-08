package org.rabix.bindings.helper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.bindings.model.DataType;
import org.rabix.bindings.model.DirectoryValue;
import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.requirement.FileRequirement;
import org.rabix.bindings.model.requirement.FileRequirement.SingleFileRequirement;
import org.rabix.bindings.model.requirement.FileRequirement.SingleInputDirectoryRequirement;
import org.rabix.bindings.model.requirement.FileRequirement.SingleInputFileRequirement;
import org.rabix.bindings.model.requirement.FileRequirement.SingleTextFileRequirement;
import org.rabix.bindings.transformer.FileTransformer;
import org.rabix.common.helper.CloneHelper;

public class FileValueHelper {

  /**
   * Creates copy of value (in common format) in which all FileValues are updated using
   * fileTransformer
   * 
   * @param value
   * @param fileTransformer
   * @return copy of value with replaced FileValues
   */
  public static Object updateFileValues(Object value, FileTransformer fileTransformer) throws BindingException {
    if (value instanceof FileValue) {
      FileValue origFile = (FileValue) value;
      return fileTransformer.transform(origFile);
    } else if (value instanceof List) {
      List<Object> ret = new ArrayList<>();
      for (Object o : (List<?>) value) {
        Object newValue = updateFileValues(o, fileTransformer);
        ret.add(newValue);
      }
      return ret;
    } else if (value instanceof Map) {
      Map<Object, Object> ret = new HashMap<>();
      for (Object key : ((Map<?, ?>) value).keySet()) {
        Object newValue = updateFileValues(((Map<?, ?>) value).get(key), fileTransformer);
        ret.put(key, newValue);
      }
      return ret;
    }
    return CloneHelper.deepCopy(value);
  }

  /**
   * Parses value (in common format) and extracts all FileValue objects
   * 
   * @param value
   * @return List of FileValue objects
   */
  public static List<FileValue> getFilesFromValue(Object value) {
    List<FileValue> ret = new ArrayList<>();
    if (value instanceof List) {
      for (Object o : (List<?>) value) {
        ret.addAll(getFilesFromValue(o));
      }
    } else if (value instanceof FileValue) {
      ret.add((FileValue) value);
    } else if (value instanceof Map) {
      for (Object key : ((Map<?, ?>) value).keySet()) {
        ret.addAll(getFilesFromValue(((Map<?, ?>) value).get(key)));
      }
    }
    return ret;
  }

  /**
   * Reads the type of input value (in common format)
   * 
   * @param value
   * @return DataType object that represents value's type
   */
  public static DataType getDataTypeFromValue(Object value) {
    if (value == null)
      return new DataType(DataType.Type.NULL);

    // DIRECTORY
    if (value instanceof DirectoryValue)
      return new DataType(DataType.Type.DIRECTORY);

    // FILE
    if (value instanceof FileValue)
      return new DataType(DataType.Type.FILE);

    // ARRAY
    if (value instanceof List) {
      Set<DataType> arrayTypes = new HashSet<>();
      DataType arrayType;
      for (Object element : (List<?>) value) {
        arrayTypes.add(getDataTypeFromValue(element));
      }

      if (arrayTypes.isEmpty())
        arrayType = new DataType(DataType.Type.EMPTY);
      else if (arrayTypes.size() == 1)
        arrayType = arrayTypes.iterator().next();
      else
        arrayType = new DataType(DataType.Type.UNION, arrayTypes);

      return new DataType(DataType.Type.ARRAY, arrayType);
    }

    // RECORD
    if (value instanceof Map) {
      Map<String, DataType> subTypes = new HashMap<>();
      Map<?, ?> valueMap = (Map<?, ?>) value;
      for (Object key : valueMap.keySet()) {
        subTypes.put((String) key, getDataTypeFromValue(valueMap.get(key)));
      }
      return new DataType(DataType.Type.RECORD, subTypes);
    }

    // PRIMITIVE
    for (DataType.Type t : DataType.Type.values()) {
      if (t.primitiveTypes != null && t.isPrimitive(value))
        return new DataType(t, value);
    }

    return new DataType(DataType.Type.ANY);
  }

  /**
   * Maps input file paths using the particular {@link FilePathMapper}
   *
   * @param job Job object
   * @param fileMapper FileMapper object
   * @return Updated Job object
   * @throws BindingException
   */
  @SuppressWarnings("unchecked")
  public static Job mapInputFilePaths(Job job, FilePathMapper fileMapper) throws BindingException {
    Map<String, Object> inputs = job.getInputs();

    Map<String, Object> clonedInputs = (Map<String, Object>) CloneHelper.deepCopy(inputs);
    try {
      mapValue(clonedInputs, fileMapper, job.getConfig());
    } catch (FileMappingException e) {
      throw new BindingException("Failed to map input file paths", e);
    }
    return Job.cloneWithInputs(job, clonedInputs);
  }

  /**
   * Maps output file paths using the particular {@link FilePathMapper}
   *
   * @param job Job object
   * @param fileMapper FileMapper object
   * @return Updated Job object
   * @throws BindingException
   */
  @SuppressWarnings("unchecked")
  public static Job mapOutputFilePaths(Job job, FilePathMapper fileMapper) throws BindingException {
    Map<String, Object> outputs = job.getOutputs();

    Map<String, Object> clonedOutputs = (Map<String, Object>) CloneHelper.deepCopy(outputs);
    try {
      mapValue(clonedOutputs, fileMapper, job.getConfig());
    } catch (FileMappingException e) {
      throw new BindingException("Failed to map output file paths", e);
    }
    return Job.cloneWithOutputs(job, clonedOutputs);
  }

  @SuppressWarnings("unchecked")
  private static void mapValue(Object value, FilePathMapper fileMapper, Map<String, Object> config) throws FileMappingException {
    if (value instanceof FileValue || value instanceof DirectoryValue) {
      FileValue fileValue = (FileValue) value;
      if (fileValue.getPath() != null) {
        fileValue.setPath(fileMapper.map(fileValue.getPath(), config));
      }
      if (fileValue.getDirname() != null) {
        fileValue.setDirname(fileMapper.map(fileValue.getDirname(), config));
      }

      List<FileValue> secondaryFiles = fileValue.getSecondaryFiles();
      if (secondaryFiles != null) {
        mapValue(secondaryFiles, fileMapper, config);
      }
      if (value instanceof DirectoryValue) {
        List<FileValue> listing = ((DirectoryValue) value).getListing();
        if (listing != null) {
          mapValue(listing, fileMapper, config);
        }
      }
      return;
    }
    if (value instanceof List<?>) {
      for (Object singleValue : (List<?>) value) {
        mapValue(singleValue, fileMapper, config);
      }
      return;
    }
    if (value instanceof Map<?, ?>) {
      for (Object singleValue : ((Map<String, Object>) value).values()) {
        mapValue(singleValue, fileMapper, config);
      }
    }
  }

  /**
   * Gets a set of input {@link FileValue} objects with their secondary files
   *
   * @param job Job object
   * @return FileValue objects
   * @throws BindingException
   */
  public static Set<FileValue> getInputFiles(Job job) throws BindingException {
    return findFiles(job.getInputs());
  }

  /**
   * Gets a set of output {@link FileValue} objects with their secondary files
   *
   * @param job Job object
   * @return FileValue objects
   * @throws BindingException
   */
  public static Set<FileValue> getOutputFiles(Job job) throws BindingException {
    return findFiles(job.getOutputs());
  }

  @SuppressWarnings("unchecked")
  private static Set<FileValue> findFiles(Object value) {
    Set<FileValue> fileValues = new HashSet<>();
    if (value instanceof FileValue || value instanceof DirectoryValue) {
      fileValues.add((FileValue) value);
      return fileValues;
    }
    if (value instanceof List<?>) {
      for (Object singleValue : (List<?>) value) {
        fileValues.addAll(findFiles(singleValue));
      }
      return fileValues;
    }
    if (value instanceof Map<?, ?>) {
      for (Object singleValue : ((Map<String, Object>) value).values()) {
        fileValues.addAll(findFiles(singleValue));
      }
      return fileValues;
    }
    return fileValues;
  }

  /**
   * Updates input files
   *
   * @param job Job object
   * @param fileTransformer FileTransformer that transforms old file values into new ones
   * @return Updated Job object
   * @throws BindingException
   */
  @SuppressWarnings("unchecked")
  public static Job updateInputFiles(Job job, FileTransformer fileTransformer) throws BindingException {
    Map<String, Object> clonedInputs = (Map<String, Object>) CloneHelper.deepCopy(job.getInputs());
    clonedInputs = (Map<String, Object>) updateFileValues(clonedInputs, fileTransformer);
    return Job.cloneWithInputs(job, clonedInputs);
  }

  /**
   * Updates output files
   *
   * @param job Job object
   * @param fileTransformer FileTransformer that transforms old file values into new ones
   * @return Updated Job object
   * @throws BindingException
   */
  @SuppressWarnings("unchecked")
  public static Job updateOutputFiles(Job job, FileTransformer fileTransformer) throws BindingException {
    Map<String, Object> clonedOutputs = (Map<String, Object>) CloneHelper.deepCopy(job.getOutputs());
    clonedOutputs = (Map<String, Object>) updateFileValues(clonedOutputs, fileTransformer);
    return Job.cloneWithOutputs(job, clonedOutputs);
  }

  public static Job stageFileRequirements(Job job, FileRequirement fileRequirementResource, Path workingDir, FilePathMapper inputFileMapper)
      throws FileMappingException, IOException {
    if (fileRequirementResource == null) {
      return job;
    }

    List<SingleFileRequirement> fileRequirements = fileRequirementResource.getFileRequirements();
    if (fileRequirements == null) {
      return job;
    }

    Map<String, Path> stagedFiles = new HashMap<>();

    for (SingleFileRequirement fileRequirement : fileRequirements) {
      Path destinationFile = workingDir.resolve(fileRequirement.getFilename());
      if (fileRequirement instanceof SingleTextFileRequirement) {
        Files.write(destinationFile, ((SingleTextFileRequirement) fileRequirement).getContent().getBytes());
        continue;
      }
      if (fileRequirement instanceof SingleInputFileRequirement || fileRequirement instanceof SingleInputDirectoryRequirement) {
        FileValue content = ((SingleInputFileRequirement) fileRequirement).getContent();
        if (FileValue.isLiteral(content)) {
          if (fileRequirement instanceof SingleInputDirectoryRequirement) {
            Files.createDirectories(destinationFile);
          } else {
            Files.createFile(destinationFile);
          }
          return job;
        }
        Path file = null;
        String loc = content.getLocation();
        URI location = URI.create(loc);
        String path;
        try {
          path = loc != null ? Paths.get(new URI(location.getScheme() == null ? "file" : location.getScheme(), location.getPath(), null)).toString()
              : content.getPath();
          String mappedPath = inputFileMapper.map(path, job.getConfig());
          stagedFiles.put(path, destinationFile);
          file = Paths.get(mappedPath);
        } catch (URISyntaxException e) {
          throw new IOException(e);
        }
        if (!Files.exists(file)) {
          continue;
        }
        boolean isLinkEnabled = ((SingleInputFileRequirement) fileRequirement).isLinkEnabled();
        if (!Files.isDirectory(file)) {
          if (isLinkEnabled) {
            Files.createSymbolicLink(destinationFile, file); // use hard link
          } else {
            Files.copy(file, destinationFile); // use copy
          }
        } else {
          FileUtils.copyDirectory(file.toFile(), destinationFile.toFile()); // use copy
        }
      }
    }

    try {
      job = FileValueHelper.updateInputFiles(job, fileValue -> {
        if (stagedFiles.containsKey(fileValue.getPath())) {
          Path path = stagedFiles.get(fileValue.getPath());
          fileValue.setPath(path.toString());
          fileValue.setLocation(path.toUri().toString());
        }
        return fileValue;
      });
    } catch (BindingException e) {
      throw new FileMappingException(e);
    }
    return job;
  }

}
