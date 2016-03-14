package org.rabix.bindings.protocol.draft2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.rabix.bindings.protocol.draft2.bean.Draft2DataLink;
import org.rabix.bindings.protocol.draft2.bean.Draft2Job;
import org.rabix.bindings.protocol.draft2.bean.Draft2JobApp;
import org.rabix.bindings.protocol.draft2.bean.Draft2OutputPort;
import org.rabix.bindings.protocol.draft2.bean.Draft2Port;
import org.rabix.bindings.protocol.draft2.bean.Draft2Step;
import org.rabix.bindings.protocol.draft2.bean.Draft2Workflow;
import org.rabix.bindings.protocol.draft2.helper.Draft2BindingHelper;
import org.rabix.bindings.protocol.draft2.helper.Draft2SchemaHelper;
import org.rabix.common.json.processor.BeanProcessor;
import org.rabix.common.json.processor.BeanProcessorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BeanProcessor} used for Job processing. It populates some additional fields.
 */
public class Draft2JobProcessor implements BeanProcessor<Draft2Job> {

  private final static Logger logger = LoggerFactory.getLogger(Draft2JobProcessor.class);
  
  public Draft2Job process(Draft2Job job) throws BeanProcessorException {
    try {
      return process(null, job);
    } catch (Draft2Exception e) {
      logger.error("Failed to process Job.", e);
      throw new BeanProcessorException(e);
    }
  }
  
  private Draft2Job process(Draft2Job parentJob, Draft2Job job) throws Draft2Exception {
    if (job.getId() == null) {
      String workflowId = parentJob != null ? parentJob.getId() : null;
      String id = workflowId != null? workflowId + Draft2SchemaHelper.PORT_ID_SEPARATOR + Draft2SchemaHelper.MASTER_JOB_ID : Draft2SchemaHelper.MASTER_JOB_ID;
      job.setId(id);
    }
    processElements(null, job);

    if (job.getApp().isWorkflow()) {
      Draft2Workflow workflow = (Draft2Workflow) job.getApp();
      for (Draft2Step step : workflow.getSteps()) {
        Draft2Job stepJob = step.getJob();
        stepJob.setId(job.getId() + "." + Draft2SchemaHelper.normalizeId(step.getId()));
        processElements(job, stepJob);
        process(job, stepJob);
      }
    }
    return job;
  }
  
  /**
   * Process Job inputs, outputs and data links
   */
  private void processElements(Draft2Job parentJob, Draft2Job job) throws Draft2Exception {
    Draft2JobApp app = job.getApp();
    if (app.isWorkflow()) {
      Draft2Workflow workflow = (Draft2Workflow) app;
      if (CollectionUtils.isEmpty(workflow.getDataLinks())) {
        createDataLinks(workflow);
      }
    }
    processPorts(parentJob, job, app.getInputs());
    processPorts(parentJob, job, app.getOutputs());
  }

  /**
   * Created data links from source properties
   */
  private void createDataLinks(Draft2Workflow workflow) throws Draft2Exception {
    for (Draft2OutputPort port : workflow.getOutputs()) {
      List<String> sources = transformSource(port.getSource());
      for (int position = 0; position < sources.size(); position++) {
        String destination = port.getId();
        Draft2DataLink dataLink = new Draft2DataLink(sources.get(position), destination, position + 1);
        workflow.addDataLink(dataLink);
      }
    }
    for (Draft2Step step : workflow.getSteps()) {
      List<Draft2DataLink> dataLinks = new ArrayList<>();
      for (Map<String, Object> input : step.getInputs()) {
        List<String> sources = transformSource(Draft2BindingHelper.getSource(input));
        for (int position = 0; position < sources.size(); position++) {
          String destination = Draft2BindingHelper.getId(input);
          Draft2DataLink dataLink = new Draft2DataLink(sources.get(position), destination, position + 1);
          dataLinks.add(dataLink);
        }
      }
      workflow.addDataLinks(dataLinks);
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> transformSource(Object source) throws Draft2Exception {
    if (source == null) {
      return Collections.<String> emptyList();
    }
    List<String> transformed = new ArrayList<>();
    if (source instanceof String) {
      transformed.add((String) source);
    } else if (source instanceof List<?>) {
      transformed.addAll((List<? extends String>) source);
    } else {
      throw new Draft2Exception("Failed to process source properties. Invalid application structure.");
    }
    return transformed;
  }

  /**
   * Process input or output ports
   */
  private void processPorts(Draft2Job parentJob, Draft2Job job, List<? extends Draft2Port> ports) throws Draft2Exception {
    for (Draft2Port port : ports) {
      String prefix = job.getId().substring(job.getId().lastIndexOf(Draft2SchemaHelper.PORT_ID_SEPARATOR) + 1) + Draft2SchemaHelper.PORT_ID_SEPARATOR;
      setScatter(job, prefix, port);  // if it's a container
      if (parentJob != null) {
        // it it's an embedded container
        setScatter(parentJob, prefix, port);
      }
      
      if (parentJob != null && parentJob.getApp().isWorkflow()) {
        // if it's a container
        Draft2Workflow workflowApp = (Draft2Workflow) parentJob.getApp();
        processDataLinks(workflowApp.getDataLinks(), port, job, true);
      }
      if (job != null && job.getApp().isWorkflow()) {
        Draft2Workflow workflowApp = (Draft2Workflow) job.getApp();
        processDataLinks(workflowApp.getDataLinks(), port, job, false);
      }
    }
  }
  
  @SuppressWarnings("unchecked")
  private void setScatter(Draft2Job job, String prefix, Draft2Port port) throws Draft2Exception {
    Object scatterObj = job.getScatter();;
    if (scatterObj != null) {
      List<String> scatterList = new ArrayList<>();
      if (scatterObj instanceof List<?>) {
        for (String scatter : ((List<String>) scatterObj)) {
          scatterList.add(Draft2SchemaHelper.normalizeId(scatter));
        }
      } else if (scatterObj instanceof String) {
        scatterList.add(Draft2SchemaHelper.normalizeId((String) scatterObj));
      } else {
        throw new Draft2Exception("Failed to process scatter properties. Invalid application structure.");
      }

      // TODO fix
      for (String scatterStr : scatterList) {
        if (scatterStr.startsWith(prefix)) {
          if ((prefix + Draft2SchemaHelper.normalizeId(port.getId())).equals(scatterStr)) {
            if (!(port.getScatter() != null && port.getScatter())) {
              port.setScatter(true);              
            }
            break;
          }
        }
      }
    }
  }

  /**
   * Process data links
   */
  private void processDataLinks(List<Draft2DataLink> dataLinks, Draft2Port port, Draft2Job job, boolean strip) {
    for (Draft2DataLink dataLink : dataLinks) {
      String source = dataLink.getSource();
      String destination = dataLink.getDestination();
      
      String scatter = null;
      if (job.getId().contains(Draft2SchemaHelper.PORT_ID_SEPARATOR)) {
        String mod = job.getId().substring(job.getId().indexOf(".") + 1);
        if (strip) {
          mod = mod.substring(mod.indexOf(Draft2SchemaHelper.PORT_ID_SEPARATOR) + 1);
        }
        scatter = Draft2SchemaHelper.ID_START + mod + Draft2SchemaHelper.PORT_ID_SEPARATOR + Draft2SchemaHelper.normalizeId(port.getId());
      } else {
        scatter = port.getId();
      }
      
      // TODO fix
      if ((source.equals(scatter) || destination.equals(scatter)) && (dataLink.getScattered() == null || !dataLink.getScattered())) {
        dataLink.setScattered(port.getScatter());
      }
    }
  }
}
