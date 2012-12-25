/*
 *  Copyright 2012 Carnegie Mellon University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package edu.cmu.lti.oaqa.ecd.phase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import mx.bigdata.anyobject.AnyObject;
import mx.bigdata.anyobject.AnyTuple;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_component.AnalysisComponent;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.impl.XmiCasSerializer;
import org.apache.uima.cas.text.AnnotationIndex;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.CasCopier;
import org.uimafit.component.JCasMultiplier_ImplBase;
import org.uimafit.descriptor.OperationalProperties;
import org.uimafit.factory.AggregateBuilder;
import org.uimafit.factory.AnalysisEngineFactory;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import edu.cmu.lti.oaqa.ecd.BaseExperimentBuilder;
import edu.cmu.lti.oaqa.ecd.ResourceHandle;
import edu.cmu.lti.oaqa.ecd.ResourceHandle.HandleType;
import edu.cmu.lti.oaqa.ecd.phase.event.PhaseEventBus;
import edu.cmu.lti.oaqa.ecd.phase.event.TerminateEvent;
import edu.cmu.lti.oaqa.ecd.util.CasUtils;
import edu.cmu.lti.oaqa.framework.types.ExperimentUUID;
import edu.cmu.lti.oaqa.framework.types.InputElement;
import edu.cmu.lti.oaqa.framework.types.ProcessingStep;

// The superclass also adds this annotation, we just want to be explicit about doing it
@OperationalProperties(outputsNewCases = true)
public final class BasePhase extends JCasMultiplier_ImplBase {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public static final String QA_INTERNAL_PHASEID = "__.qa.internal.phaseid.__";

  private static final String TIMEOUT_KEY = "option-timeout";

  private static final String CROSS_PARAMS_KEY = "cross-opts";

  private static final int DEFAULT_OPTION_TIMEOUT = 5;

  private AnalysisEngine[] options;

  private int nextAnnotator;

  private JCas cas;

  private Integer phaseNo;

  private Integer optionTimeout;

  private String phaseName;

  private PhasePersistenceProvider persistence;

  @Override
  public void initialize(UimaContext ctx) throws ResourceInitializationException {
    super.initialize(ctx);
    String pp = (String) ctx.getConfigParameterValue("persistence-provider");
    if (pp == null) {
      throw new ResourceInitializationException(new IllegalArgumentException(
              "Must provide a parameter of type <persistence-provider>"));
    }
    this.persistence = BaseExperimentBuilder.loadProvider(pp, PhasePersistenceProvider.class);
    this.phaseName = (String) ctx.getConfigParameterValue("name");
    this.phaseNo = (Integer) ctx.getConfigParameterValue(QA_INTERNAL_PHASEID);
    this.optionTimeout = (Integer) ctx.getConfigParameterValue(TIMEOUT_KEY);
    if (optionTimeout == null) {
      this.optionTimeout = DEFAULT_OPTION_TIMEOUT;
    }
    System.out.println("Phase: " + toString());
    String experimentId = (String) ctx
            .getConfigParameterValue(BaseExperimentBuilder.EXPERIMENT_UUID_PROPERTY);
    String optDescr = (String) ctx.getConfigParameterValue("options");
    this.options = loadOptions(optDescr, ctx);
    for (AnalysisEngine ae : options) {
      System.out.println("\t- " + ae.getAnalysisEngineMetaData().getName());
    }
    System.out.println(" Total # of options configured: " + size());

    int stageId = (Integer) ctx.getConfigParameterValue(BaseExperimentBuilder.STAGE_ID_PROPERTY);
    persistence.insertExperimentMeta(experimentId, phaseNo, stageId, size());
    nextAnnotator = 0;
  }

  @Override
  public void process(JCas aJCas) throws AnalysisEngineProcessException {
    cas = aJCas;
    nextAnnotator = 0;
  }

  @Override
  public boolean hasNext() throws AnalysisEngineProcessException {
    return (nextAnnotator < options.length);
  }

  @Override
  public void collectionProcessComplete() throws AnalysisEngineProcessException {
    for (AnalysisEngine ae : options) {
      ae.collectionProcessComplete();
    }
    executor.shutdown();
  }

  public Integer getPhaseNo() {
    return phaseNo;
  }

  @Override
  public JCas next() throws AnalysisEngineProcessException {
    AnalysisEngine ae = options[nextAnnotator];
    JCas nextCas = getEmptyJCas();
    try {
      greedyCopy(cas, nextCas);
      try {
        AnnotationIndex<Annotation> prevSteps = nextCas.getAnnotationIndex(ProcessingStep.type);
        String prevCasId = ProcessingStepUtils.getPreviousCasId(prevSteps);
        Trace prevTrace = ProcessingStepUtils.getTrace(prevSteps);
        String optionId = ae.getAnalysisEngineMetaData().getName();
        String sequenceId = ProcessingStepUtils.getSequenceId(nextCas);
        Trace trace = ProcessingStepUtils.getPartialTrace(prevTrace.getTrace(), getPhaseNo(),
                optionId);
        if (!loadCasFromStorage(nextCas, trace, sequenceId)) {
          // TODO: Why do we call process from next() method?
          // See:
          // http://uima.apache.org/downloads/releaseDocs/2.3.0-incubating/docs/api/org/apache/uima/analysis_component/JCasMultiplier_ImplBase.html
          process(ae, nextCas, prevCasId, prevTrace, optionId, sequenceId, trace);
        }
        nextAnnotator++; // TODO:Should this be in a final clause?
        return nextCas;
      } catch (Exception e) {
        throw new AnalysisEngineProcessException(e);
      }
    } catch(Exception e) {
      // Release if greedyCopy fails
      nextCas.release();
      throw new AnalysisEngineProcessException(e);
    }
  }

  private void process(final AnalysisEngine ae, JCas nextCas, String prevCasId, Trace prevTrace,
          String optionId, String sequenceId, Trace trace) throws IOException, SAXException, Exception {
    long a = System.currentTimeMillis();
    final String uuid = ProcessingStepUtils.getCurrentExperimentId(nextCas);
    final String key = ProcessingStepUtils.getExecutionIdHash(uuid, trace, sequenceId);
    try {
      insertExecutionTrace(nextCas, optionId, a, prevCasId, trace, key);
      System.out.printf("[%s] Executing option: %s on trace %s\n", sequenceId, optionId, prevTrace);
      // Wrap the JCas to ignore downstream method invocation in case component timeout.
      final DeferredTerminationJCasWrapper wrapped = new DeferredTerminationJCasWrapper(nextCas);
      // We are using a single thread executor for each phase, this means that
      // at any point no more than a single task should be executed until
      // the previous task finishes or dies
      Future<?> future = executor.submit(new Runnable() {
        @Override
        public void run() {
          try {
            // The execution id hash is added just before the future is executed
            wrapped.addExecutionIdHash(key);
            // Here is where the actual option is processed
            ae.process(wrapped);
          } catch (Exception e) {
            // Propagate the exception so it gets printed.
            // An async mechanism should be used to get the exception
            // back into the calling thread
            Throwables.propagate(e);
          }
        }
      });
      try {
        future.get(optionTimeout, TimeUnit.MINUTES);
        long b = System.currentTimeMillis();
        addProcessingStep(nextCas, optionId, key);
        storeCas(nextCas, b, key);
        System.out.printf("[%s]  Execution time for option %s: %ss\n", sequenceId, optionId,
                (b - a) / 1000);
      } catch (TimeoutException e) {
        // If the AE is taking too long and it times out it could be either:
        // 1) It's reading from a resource or
        // 2) it's stuck on a iterative algorithm.

        // First send a termination event so the AE is aware that it's been terminated.
        // Classes that inherit from TerminableComponent have access to this information
        // and should check if the component has been terminated.
        PhaseEventBus.sendTerminateEvent(new TerminateEvent(key));
        // The cancel(true) method will call notify, any waiting IO operations will be interrupted,
        // with an InterruptedIOException and the component be rendered invalid.
        future.cancel(true);
        // We invalidate the JCas wrapper, so any subsequent calls to it will throw an Exception
        wrapped.invalidate();
        long b = System.currentTimeMillis();
        storeException(b, e, key, ExecutionStatus.TIMEOUT);
        System.out.printf("[%s]  Execution timed out for option: %s after %ss\n", sequenceId,
                optionId, (b - a) / 1000);
        // Finally re-throw the exception to allow the flow controller do its job
        throw e;
      }
    } catch (Exception e) {
      long b = System.currentTimeMillis();
      try {
        storeException(b, e, key, ExecutionStatus.FAILURE);
        System.out.printf("[%s]  Execution failed for option: %s after %ss\n", sequenceId,
                optionId, (b - a) / 1000);
      } finally {
        nextCas.release();
      }
      throw e; // Re-throw exception to allow the Flow controller do its job
    }
  }

  private void greedyCopy(JCas existingCas, JCas emptyCas) {
    // nothing 'greedy' about the default implementation, which copies everything.
    CasCopier.copyCas(existingCas.getCas(), emptyCas.getCas(), true);
  }

  private void addProcessingStep(JCas jcas, String optionId, String key) {
    ProcessingStep s = new ProcessingStep(jcas);
    s.setComponent(optionId);
    s.setPhaseId(getPhaseNo());
    s.setCasId(key);
    s.addToIndexes();
  }

  private void insertExecutionTrace(JCas jcas, final String optionId, final long startTime,
          final String prevCas, final Trace trace, final String key) throws IOException {
    final String uuid = ProcessingStepUtils.getCurrentExperimentId(jcas);
    InputElement input = (InputElement) CasUtils.getFirst(jcas, InputElement.class.getName());
    final String dataset = input.getDataset();
    final String sequenceId = input.getSequenceId();
    persistence.insertExecutionTrace(optionId, sequenceId, dataset, getPhaseNo(), uuid, startTime,
            getHostName(), trace.getTrace(), key);
  }

  private void storeCas(JCas jcas, final long endTime, final String key) throws IOException,
          SAXException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GZIPOutputStream gz = new GZIPOutputStream(baos);
    XmiCasSerializer.serialize(jcas.getCas(), gz);
    gz.finish();
    final byte[] bytes = baos.toByteArray();
    persistence.storeCas(bytes, ExecutionStatus.SUCCESS, endTime, key);
  }

  private void storeException(final long endTime, Exception e, final String key,
          ExecutionStatus status) throws IOException, SAXException {
    Throwable rootCause = Throwables.getRootCause(e);
    String rootCauseString = Throwables.getStackTraceAsString(rootCause);
    final byte[] bytes = rootCauseString.getBytes("UTF8");
    persistence.storeException(bytes, status, endTime, key);
  }

  private boolean loadCasFromStorage(JCas jcas, Trace trace, String sequenceId) throws SQLException {
    ExperimentUUID experiment = ProcessingStepUtils.getCurrentExperiment(jcas);
    String experimentId = experiment.getUuid();
    int stageId = experiment.getStageId();
    final String hash = ProcessingStepUtils.getExecutionIdHash(experimentId, trace, sequenceId);
    CasDeserializer deserializer = persistence.deserialize(jcas, hash);
    if (deserializer.processedCas()) {
      ExperimentUUID expUuid = new ExperimentUUID(jcas);
      expUuid.setUuid(experimentId);
      expUuid.setStageId(stageId);
      expUuid.addToIndexes();
      System.err.printf("Loaded cas for %s @ %s\n", sequenceId, trace.getTrace());
    }
    return deserializer.processedCas();
  }

  private String getHostName() throws IOException {
    InetAddress addr = InetAddress.getLocalHost();
    return addr.getHostName();
  }

  private AnalysisEngine[] loadOptions(String options, UimaContext c) {
    List<AnalysisEngineDescription> aeds = loadOptions(options);
    List<AnalysisEngine> aes = new ArrayList<AnalysisEngine>();
    for (AnalysisEngineDescription aeDesc : aeds) {
      try {
        aes.add(AnalysisEngineFactory.createAggregate(aeDesc));
      } catch (ResourceInitializationException e) {
        e.printStackTrace();
      }
    }
    return aes.toArray(new AnalysisEngine[0]);
  }

  List<AnalysisEngineDescription> loadOptions(String options) {
    List<AnalysisEngineDescription> aeds = new ArrayList<AnalysisEngineDescription>();
    Yaml yaml = new Yaml();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ao = (List<Map<String, Object>>) yaml.load(options);
    for (Map<String, Object> optionDescription : ao) {
      loadOption(optionDescription, aeds);
    }
    return aeds;
  }

  @SuppressWarnings("unchecked")
  void loadOption(Map<String, Object> description, List<AnalysisEngineDescription> aes) {
    try {
      Map.Entry<String, Object> first = getFirst(description);
      HandleType type = HandleType.getInstance(first.getKey());
      if (type == HandleType.PIPELINE) {
        List<Map<String, Object>> pipeline = (List<Map<String, Object>>) first.getValue();
        List<AnalysisEngineDescription> options = createInnerPipeline(pipeline);
        aes.addAll(options);
      } else {
        String resource = (String) first.getValue();
        List<AnalysisEngineDescription> options = doLoadOptions(ResourceHandle.newHandle(type,
                resource));
        aes.addAll(options);
      }
    } catch (Exception e) {
      System.err.printf("Unable to load option %s caused by:\n", description);
      Throwable cause = e.getCause();
      if (cause != null) {
        cause.printStackTrace();
      } else {
        e.printStackTrace();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private List<AnalysisEngineDescription> createInnerPipeline(List<Map<String, Object>> pipeline)
          throws ResourceInitializationException, IOException {
    List<AnalysisEngineDescription> options = Lists.newArrayList();
    List<Set<AnalysisEngineDescription>> sets = Lists.newArrayList();
    for (Map<String, Object> map : pipeline) {
      try {
        Map.Entry<String, Object> me = getFirst(map);
        String type = me.getKey();
        Object o = me.getValue();
        if (o instanceof String) {
          String component = ((String) o).trim();
          ResourceHandle handle = ResourceHandle.newHandle(type, component);
          Set<AnalysisEngineDescription> local = Sets.newLinkedHashSet(doLoadOptions(handle));
          sets.add(local);
        } else if (o instanceof Iterable) {
          Iterable<Object> components = (Iterable<Object>) o;
          Set<AnalysisEngineDescription> local = Sets.newLinkedHashSet();
          for (Object o2 : components) {
            if (o2 instanceof Map) {
              Map<String, Object> component = (Map<String, Object>) o2;
              List<AnalysisEngineDescription> aes = new ArrayList<AnalysisEngineDescription>();
              loadOption(component, aes);
              local.addAll(aes);
            } else {
              throw new IllegalArgumentException(
                      "Illegal experiment descriptor, all options must be specified as a pair 'key: value'");
            }
          }
          sets.add(local);
        } else {
          throw new IllegalArgumentException(
                  "Illegal experiment descriptor, must contain either an iterable or a string");
        }
      } catch (Exception e) {
        System.err.printf("Unable to load option %s caused by:\n", map);
        Throwable cause = e.getCause();
        if (cause != null) {
          cause.printStackTrace();
        } else {
          e.printStackTrace();
        }
      }
    }
    // AED equality is based on equality of the MetaDataObject attributes
    Set<List<AnalysisEngineDescription>> product = Sets.cartesianProduct(sets);
    for (List<AnalysisEngineDescription> local : product) {
      AggregateBuilder builder = new AggregateBuilder();
      List<String> names = Lists.newArrayList();
      for (AnalysisEngineDescription aeDesc : local) {
        builder.add(aeDesc);
        names.add(aeDesc.getAnalysisEngineMetaData().getName());
      }
      String aeName = Joiner.on(";").join(names);
      AnalysisEngineDescription aee = builder.createAggregateDescription();
      aee.getAnalysisEngineMetaData().setName(String.format("pipeline:(%s)", aeName));
      options.add(aee);
    }
    return options;
  }

  private List<AnalysisEngineDescription> doLoadOptions(ResourceHandle handle) throws Exception {
    List<AnalysisEngineDescription> aes = Lists.newArrayList();
    Map<String, Object> tuples = Maps.newLinkedHashMap();
    Class<? extends AnalysisComponent> comp = BaseExperimentBuilder.loadFromClassOrInherit(handle,
            AnalysisComponent.class, tuples);
    AnyObject crossParams = (AnyObject) tuples.remove(CROSS_PARAMS_KEY);
    if (crossParams == null) {
      AnalysisEngineDescription aeDesc = BaseExperimentBuilder.createAnalysisEngineDescription(
              tuples, comp);
      aes.add(aeDesc);
    } else {
      List<String> paramNames = getParameterNames(crossParams);
      Set<List<Object>> product = doCartesianProduct(crossParams);
      for (List<Object> configuration : product) {
        Map<String, Object> inner = Maps.newLinkedHashMap(tuples);
        setInnerParams(paramNames, configuration, inner);
        AnalysisEngineDescription aeDesc = BaseExperimentBuilder.createAnalysisEngineDescription(
                inner, comp);
        aes.add(aeDesc);
      }
    }
    return aes;
  }

  private List<String> getParameterNames(AnyObject crossParams) {
    List<String> names = Lists.newArrayList(); // parameter names
    for (AnyTuple tuple : crossParams.getTuples()) {
      String key = tuple.getKey();
      names.add(key);
    }
    return names;
  }

  private Set<List<Object>> doCartesianProduct(AnyObject crossParams) {
    List<Set<Object>> sets = Lists.newArrayList(); // input parameters
    List<String> names = Lists.newArrayList(); // parameter names
    for (AnyTuple tuple : crossParams.getTuples()) {
      Set<Object> params = Sets.newHashSet();
      String key = tuple.getKey();
      names.add(key);
      @SuppressWarnings("unchecked")
      Iterable<Object> values = (Iterable<Object>) tuple.getObject();
      for (Object value : values) {
        params.add(value);
      }
      sets.add(params);
    }
    Set<List<Object>> product = Sets.cartesianProduct(sets);
    return product;
  }

  private void setInnerParams(List<String> paramNames, List<Object> configuration,
          Map<String, Object> inner) {
    for (int i = 0; i < paramNames.size(); i++) {
      String key = paramNames.get(i);
      Object value = configuration.get(i);
      inner.put(key, value);
    }
  }

  private Map.Entry<String, Object> getFirst(Map<String, Object> map) {
    return Iterators.get(map.entrySet().iterator(), 0);
  }

  @Override
  public String toString() {
    return String.format("%s|%s>", getPhaseNo(), phaseName);
  }

  int size() {
    return options.length;
  }
}
