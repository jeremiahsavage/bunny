package org.rabix.engine.helper;

import com.google.inject.Inject;

import org.rabix.bindings.model.dag.DAGLinkPort;
import org.rabix.common.helper.InternalSchemaHelper;
import org.rabix.engine.service.LinkRecordService;
import org.rabix.engine.service.VariableRecordService;
import org.rabix.engine.store.model.LinkRecord;
import org.rabix.engine.store.model.VariableRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class TerminalOutputsHelper {

  private static final Logger logger = LoggerFactory.getLogger(TerminalOutputsHelper.class);

  private final VariableRecordService variableRecordService;
  private final LinkRecordService linkRecordService;

  @Inject
  public TerminalOutputsHelper(VariableRecordService variableRecordService,
                               LinkRecordService linkRecordService){
    this.variableRecordService = variableRecordService;
    this.linkRecordService = linkRecordService;
  }

  public Map<String, Object> getTerminalOutputs(String jobId, UUID rootId){
    List<LinkRecord> linkRecords = linkRecordService.findBySourceAndSourceType(jobId,
                                                                               DAGLinkPort.LinkPortType.OUTPUT,
                                                                               rootId);
    // get all links which are leading to root job
    linkRecords.removeIf(linkRecord -> !linkRecord.getDestinationJobId().equals(InternalSchemaHelper.ROOT_NAME));
    Map<String, Object> terminalOutputs = new HashMap<>();

    // for links leading to root job find outputs which will be terminal outputs for current job (if the job have any)
    for(LinkRecord linkRecord : linkRecords){
      VariableRecord variableRecord =  variableRecordService.find(InternalSchemaHelper.ROOT_NAME,
                                                                  linkRecord.getDestinationJobPort(),
                                                                  DAGLinkPort.LinkPortType.OUTPUT, rootId);
      if(variableRecord != null){
        terminalOutputs.put(linkRecord.getDestinationJobPort(), variableRecord.getValue());
      }
    }

    return terminalOutputs;
  }
}
