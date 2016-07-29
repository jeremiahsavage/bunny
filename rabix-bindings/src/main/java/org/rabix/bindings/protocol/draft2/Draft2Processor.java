package org.rabix.bindings.protocol.draft2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.ProtocolProcessor;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.protocol.draft2.bean.Draft2CommandLineTool;
import org.rabix.bindings.protocol.draft2.bean.Draft2ExpressionTool;
import org.rabix.bindings.protocol.draft2.bean.Draft2Job;
import org.rabix.bindings.protocol.draft2.bean.Draft2JobApp;
import org.rabix.bindings.protocol.draft2.bean.Draft2OutputPort;
import org.rabix.bindings.protocol.draft2.expression.Draft2ExpressionException;
import org.rabix.bindings.protocol.draft2.expression.helper.Draft2ExpressionBeanHelper;
import org.rabix.bindings.protocol.draft2.helper.Draft2BindingHelper;
import org.rabix.bindings.protocol.draft2.helper.Draft2FileValueHelper;
import org.rabix.bindings.protocol.draft2.helper.Draft2JobHelper;
import org.rabix.bindings.protocol.draft2.helper.Draft2SchemaHelper;
import org.rabix.bindings.protocol.draft2.processor.Draft2PortProcessorException;
import org.rabix.bindings.protocol.draft2.processor.callback.Draft2PortProcessorHelper;
import org.rabix.bindings.protocol.draft2.service.Draft2GlobException;
import org.rabix.bindings.protocol.draft2.service.Draft2GlobService;
import org.rabix.bindings.protocol.draft2.service.Draft2MetadataService;
import org.rabix.bindings.protocol.draft2.service.impl.Draft2GlobServiceImpl;
import org.rabix.bindings.protocol.draft2.service.impl.Draft2MetadataServiceImpl;
import org.rabix.common.helper.ChecksumHelper.HashAlgorithm;
import org.rabix.common.helper.JSONHelper;
import org.rabix.common.json.BeanSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Draft2Processor implements ProtocolProcessor {

  public final static int DEFAULT_SUCCESS_CODE = 0;
  
  private final static String JOB_FILE = "job.json";
  private final static String resultFilename = "cwl.output.json";
  
  private final static Logger logger = LoggerFactory.getLogger(Draft2Processor.class);

  private final Draft2GlobService globService;
  private final Draft2MetadataService metadataService;

  public Draft2Processor() {
    this.globService = new Draft2GlobServiceImpl();
    this.metadataService = new Draft2MetadataServiceImpl();
  }

  @Override
  public Job preprocess(final Job job, final File workingDir) throws BindingException {
    Draft2Job draft2Job = Draft2JobHelper.getDraft2Job(job);
    Draft2PortProcessorHelper portProcessorHelper = new Draft2PortProcessorHelper(draft2Job);
    try {
      File jobFile = new File(workingDir, JOB_FILE);
      String serializedJob = BeanSerializer.serializePartial(Draft2JobHelper.getDraft2Job(job));
      FileUtils.writeStringToFile(jobFile, serializedJob);
      
      Map<String, Object> inputs = job.getInputs();
      inputs = portProcessorHelper.setFileSize(inputs);
      inputs = portProcessorHelper.loadInputContents(inputs);
      inputs = portProcessorHelper.stageInputFiles(inputs, workingDir);
      return Job.cloneWithInputs(job, inputs);
    } catch (Draft2PortProcessorException | IOException e) {
      throw new BindingException(e);
    }
  }
  
  @Override
  public boolean isSuccessful(Job job, int statusCode) throws BindingException {
    Draft2Job draft2Job = Draft2JobHelper.getDraft2Job(job);
    List<Integer> successCodes = draft2Job.getApp().getSuccessCodes();

    if (successCodes == null) {
      successCodes = new ArrayList<>();
    }
    if (successCodes.isEmpty()) {
      successCodes.add(DEFAULT_SUCCESS_CODE);
    }
    for (Integer successCode : successCodes) {
      if (successCode.intValue() == statusCode) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Job postprocess(Job job, File workingDir) throws BindingException {
    Draft2Job draft2Job = Draft2JobHelper.getDraft2Job(job);
    try {
      Map<String, Object> outputs = null;

      if (draft2Job.getApp().isExpressionTool()) {
        Draft2ExpressionTool expressionTool = (Draft2ExpressionTool) draft2Job.getApp();
        try {
          outputs = Draft2ExpressionBeanHelper.evaluate(draft2Job, expressionTool.getScript());
        } catch (Draft2ExpressionException e) {
          throw new BindingException("Failed to populate outputs", e);
        }
      } else {
        outputs = collectOutputs(draft2Job, workingDir, null);
      }
      outputs = new Draft2PortProcessorHelper(draft2Job).fixOutputMetadata(draft2Job.getInputs(), outputs);
      return Job.cloneWithOutputs(job, outputs);
    } catch (Draft2GlobException | Draft2ExpressionException | IOException | Draft2PortProcessorException e) {
      throw new BindingException(e);
    }
  }
  
  private Map<String, Object> collectOutputs(Draft2Job job, File workingDir, HashAlgorithm hashAlgorithm) throws Draft2GlobException, Draft2ExpressionException, IOException, BindingException {
    File resultFile = new File(workingDir, resultFilename);
    
    if (resultFile.exists()) {
      String resultStr = FileUtils.readFileToString(resultFile);
      return JSONHelper.readMap(resultStr);
    }
    
    Map<String, Object> result = new HashMap<>();
    Draft2CommandLineTool commandLineTool = (Draft2CommandLineTool) job.getApp();
    for (Draft2OutputPort outputPort : commandLineTool.getOutputs()) {
      Object singleResult = collectOutput(job, workingDir, hashAlgorithm, outputPort.getSchema(), outputPort.getOutputBinding(), outputPort);
      if (singleResult != null) {
        result.put(Draft2SchemaHelper.normalizeId(outputPort.getId()), singleResult);
      }
    }
    BeanSerializer.serializePartial(resultFile, result);
    return result;
  }

  @SuppressWarnings("unchecked")
  private Object collectOutput(Draft2Job job, File workingDir, HashAlgorithm hashAlgorithm, Object schema, Object binding, Draft2OutputPort outputPort) throws Draft2GlobException, Draft2ExpressionException, BindingException {
    if (binding == null) {
      binding = Draft2SchemaHelper.getOutputBinding(schema);
    }
    logger.debug("Collecting outputs for {}.", job.getId());

    if (schema == null) {
      schema = Draft2SchemaHelper.TYPE_JOB_FILE;
    }

    Object result = null;
    if (Draft2SchemaHelper.isArrayFromSchema(schema)) {
      Draft2JobApp app = job.getApp();
      Object itemSchema = Draft2SchemaHelper.getSchemaForArrayItem(app.getSchemaDefs(), schema);
      if (itemSchema == null) {
        return null;
      }

      if (itemSchema.equals(Draft2SchemaHelper.TYPE_JOB_FILE) || Draft2SchemaHelper.isFileFromSchema(itemSchema)) {
        Object itemBinding = Draft2SchemaHelper.getOutputBinding(itemSchema);
        if (itemBinding != null) {
          binding = itemBinding;
        }
        result = globFiles(job, workingDir, hashAlgorithm, outputPort, binding);
      } else {
        result = collectOutput(job, workingDir, hashAlgorithm, itemSchema, binding, outputPort);
      }
    } else if (Draft2SchemaHelper.isRecordFromSchema(schema)) {
      Map<String, Object> record = new HashMap<>();
      Object fields = Draft2SchemaHelper.getFields(schema);

      if (fields instanceof List<?>) {
        List<Object> fieldList = (List<Object>) fields;
        for (Object field : fieldList) {
          Map<String, Object> fieldMap = (Map<String, Object>) field;

          String id = (String) fieldMap.get(Draft2SchemaHelper.KEY_SCHEMA_NAME);
          Object fieldSchema = fieldMap.get(Draft2SchemaHelper.KEY_SCHEMA_TYPE);
          Object fieldBinding = Draft2SchemaHelper.getOutputBinding(fieldMap);
          if (fieldBinding != null) {
            binding = fieldBinding;
          }
          Object singleResult = collectOutput(job, workingDir, hashAlgorithm, fieldSchema, binding, outputPort);
          if (singleResult != null) {
            record.put(id, singleResult);
          }
        }
      }
      result = record;
    } else {
      result = globFiles(job, workingDir, hashAlgorithm, outputPort, binding);
    }
    Object outputEval = Draft2BindingHelper.getOutputEval(binding);
    if (outputEval != null) {
      result = Draft2BindingHelper.evaluateOutputEval(job, result, binding);
      logger.info("OutputEval transformed result into {}.", result);
    }
    if (Draft2SchemaHelper.isFileFromSchema(schema) || Draft2SchemaHelper.isFileFromValue(((List<?>)result).get(0))) {
      switch (((List<?>) result).size()) {
      case 0:
        result = null;
        break;
      case 1:
        result = ((List<?>) result).get(0);
        break;
      default:
        throw new BindingException("Invalid file format " + result);
      }
    }
    if (result instanceof List<?>) {
      if (Draft2SchemaHelper.isFileFromSchema(schema) || Draft2SchemaHelper.isFileFromValue(((List<?>)result).get(0))) {
        switch (((List<?>) result).size()) {
        case 0:
          result = null;
          break;
        case 1:
          result = ((List<?>) result).get(0);
          break;
        default:
          throw new BindingException("Invalid file format " + result);
        }
      }
    }
    return result;

  }

  /**
   * Extracts files from a directory based on GLOB expression
   */
  private List<Map<String, Object>> globFiles(final Draft2Job job, final File workingDir, HashAlgorithm hashAlgorithm, final Draft2OutputPort outputPort, Object outputBinding) throws Draft2GlobException {
    if (outputPort.getOutputBinding() != null) {
      outputBinding = outputPort.getOutputBinding(); // override
    }
    if (outputBinding == null) {
      logger.debug("Output binding is empty. Skip output extraction.");
      return null;
    }

    Object glob = Draft2BindingHelper.getGlob(outputBinding);
    if (glob == null) {
      logger.debug("GLOB does not exist. Skip output extraction.");
      return null;
    }

    Set<File> paths = globService.glob(job, workingDir, glob);
    if (paths == null) {
      logger.info("Glob service didn't find any files.");
      return null;
    }
    logger.debug("Glob service returned result {}", paths);

    final List<Map<String, Object>> result = new ArrayList<>();
    for (File path : paths) {
      try {
        logger.info("Processing {}.", path);
        File file = path;
        Map<String, Object> fileData = new HashMap<>();
        Draft2FileValueHelper.setFileType(fileData);
        if (hashAlgorithm != null) {
          Draft2FileValueHelper.setChecksum(file, fileData, hashAlgorithm);
        }
        Draft2FileValueHelper.setSize(file.length(), fileData);
        Draft2FileValueHelper.setName(file.getName(), fileData);
        Draft2FileValueHelper.setPath(file.getAbsolutePath(), fileData);

        List<?> secondaryFiles = getSecondaryFiles(job, hashAlgorithm, fileData, file.getAbsolutePath(), outputBinding);
        if (secondaryFiles != null) {
          Draft2FileValueHelper.setSecondaryFiles(secondaryFiles, fileData);
        }
        Object metadata = Draft2BindingHelper.getMetadata(outputBinding);
        metadata = metadataService.evaluateMetadataExpressions(job, fileData, metadata);
        logger.info("Metadata expressions evaluated. Metadata is {}.", metadata);
        if (metadata != null) {
          Draft2FileValueHelper.setMetadata(metadata, fileData);
        }
        metadata = metadataService.processMetadata(job, fileData, outputPort, outputBinding);
        if (metadata != null) {
          logger.info("Metadata for {} resolved. Metadata is {}", outputPort.getId(), metadata);
          Draft2FileValueHelper.setMetadata(metadata, fileData);
        } else {
          logger.info("Metadata for {} output is empty.", outputPort.getId());
        }
        result.add(fileData);

        boolean loadContents = Draft2BindingHelper.loadContents(outputBinding);
        if (loadContents) {
          Draft2FileValueHelper.setContents(fileData);
        }
      } catch (Exception e) {
        logger.error("Failed to extract outputs", e);
        throw new Draft2GlobException("Failed to extract outputs.", e);
      }
    }
    return result;
  }

  /**
   * Gets secondary files (absolute paths)
   */
  private List<Map<String, Object>> getSecondaryFiles(Draft2Job job, HashAlgorithm hashAlgorithm, Map<String, Object> fileValue, String fileName, Object binding) throws Draft2ExpressionException {
    List<String> secondaryFileSufixes = Draft2BindingHelper.getSecondaryFiles(binding);

    if (secondaryFileSufixes == null) {
      return null;
    }

    List<Map<String, Object>> secondaryFileMaps = new ArrayList<>();
    for (String suffix : secondaryFileSufixes) {
      String secondaryFilePath = fileName.toString();

      if (Draft2ExpressionBeanHelper.isExpression(suffix)) {
        secondaryFilePath = Draft2ExpressionBeanHelper.evaluate(job, fileValue, suffix);
      } else {
        while (suffix.startsWith("^")) {
          int extensionIndex = secondaryFilePath.lastIndexOf(".");
          if (extensionIndex != -1) {
            secondaryFilePath = secondaryFilePath.substring(0, extensionIndex);
            suffix = suffix.substring(1);
          } else {
            break;
          }
        }
        secondaryFilePath += suffix.startsWith(".") ? suffix : "." + suffix;
      }
      File secondaryFile = new File(secondaryFilePath);
      if (secondaryFile.exists()) {
        Map<String, Object> secondaryFileMap = new HashMap<>();
        Draft2FileValueHelper.setFileType(secondaryFileMap);
        Draft2FileValueHelper.setPath(secondaryFile.getAbsolutePath(), secondaryFileMap);
        Draft2FileValueHelper.setSize(secondaryFile.length(), secondaryFileMap);
        Draft2FileValueHelper.setName(secondaryFile.getName(), secondaryFileMap);
        if (hashAlgorithm != null) {
          Draft2FileValueHelper.setChecksum(secondaryFile, secondaryFileMap, hashAlgorithm);
        }
        secondaryFileMaps.add(secondaryFileMap);
      }
    }
    return secondaryFileMaps;
  }

}
