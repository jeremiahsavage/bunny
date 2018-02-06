package org.rabix.bindings.sb.processor.callback;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.mapper.FilePathMapper;
import org.rabix.bindings.model.ApplicationPort;
import org.rabix.bindings.sb.helper.SBFileValueHelper;
import org.rabix.bindings.sb.helper.SBSchemaHelper;
import org.rabix.bindings.sb.processor.SBPortProcessorCallback;
import org.rabix.bindings.sb.processor.SBPortProcessorException;
import org.rabix.bindings.sb.processor.SBPortProcessorResult;
import org.rabix.common.helper.CloneHelper;

public class SBFilePathMapProcessorCallback implements SBPortProcessorCallback {

  private final FilePathMapper filePathMapper;
  private final Map<String, Object> config;

  public SBFilePathMapProcessorCallback(FilePathMapper filePathMapper, Map<String, Object> config) {
    this.config = config;
    this.filePathMapper = filePathMapper;
  }

  @Override
  @SuppressWarnings("unchecked")
  public SBPortProcessorResult process(Object value, String id, Object schema, Object binding, ApplicationPort parentPort) throws SBPortProcessorException {
    if (value == null) {
      return new SBPortProcessorResult(value, false);
    }
    try {
      Object clonedValue = CloneHelper.deepCopy(value);
      
      if (SBSchemaHelper.isFileFromValue(clonedValue)) {
        Map<String, Object> valueMap = (Map<String, Object>) clonedValue;
        String path = SBFileValueHelper.getPath(valueMap);

        if (path != null && filePathMapper != null) {
          mapAllValues(clonedValue);

          List<Map<String, Object>> secondaryFiles = SBFileValueHelper.getSecondaryFiles(valueMap);

          if (secondaryFiles != null) {
            for (Map<String, Object> secondaryFile : secondaryFiles) {
              mapAllValues(secondaryFile);
            }
          }
          return new SBPortProcessorResult(valueMap, true);
        }
      }
      return new SBPortProcessorResult(clonedValue, false);
    } catch (Exception e) {
      throw new SBPortProcessorException(e);
    }
    
  }
  private void mapAllValues(Object secondaryFileValue) throws FileMappingException {
    String mappedValue = filePathMapper.map(SBFileValueHelper.getPath(secondaryFileValue), config);
    SBFileValueHelper.setPath(mappedValue, secondaryFileValue);
    Path pathObj = Paths.get(mappedValue);
    SBFileValueHelper.setLocation(pathObj.toUri().toString(), secondaryFileValue);
    SBFileValueHelper.setDirname(pathObj.getParent().toString(), secondaryFileValue);
  }
}
