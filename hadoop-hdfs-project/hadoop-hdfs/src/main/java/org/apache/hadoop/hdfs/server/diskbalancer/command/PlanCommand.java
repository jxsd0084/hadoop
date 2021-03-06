/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.hdfs.server.diskbalancer.command;

import com.google.common.base.Preconditions;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.ClientDatanodeProtocol;
import org.apache.hadoop.hdfs.server.diskbalancer.DiskBalancerConstants;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel
    .DiskBalancerDataNode;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel.DiskBalancerVolume;
import org.apache.hadoop.hdfs.server.diskbalancer.datamodel
    .DiskBalancerVolumeSet;
import org.apache.hadoop.hdfs.server.diskbalancer.planner.NodePlan;
import org.apache.hadoop.hdfs.server.diskbalancer.planner.Step;
import org.apache.hadoop.hdfs.tools.DiskBalancer;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class that implements Plan Command.
 * <p>
 * Plan command reads the Cluster Info and creates a plan for specified data
 * node or a set of Data nodes.
 * <p>
 * It writes the output to a default location unless changed by the user.
 */
public class PlanCommand extends Command {
  private double thresholdPercentage;
  private int bandwidth;
  private int maxError;

  /**
   * Constructs a plan command.
   */
  public PlanCommand(Configuration conf) {
    super(conf);
    this.thresholdPercentage = 1;
    this.bandwidth = 0;
    this.maxError = 0;
    addValidCommandParameters(DiskBalancer.OUTFILE, "Output directory in " +
        "HDFS. The generated plan will be written to a file in this " +
        "directory.");
    addValidCommandParameters(DiskBalancer.BANDWIDTH, "Maximum Bandwidth to " +
        "be used while copying.");
    addValidCommandParameters(DiskBalancer.THRESHOLD, "Percentage skew that " +
        "we tolerate before diskbalancer starts working.");
    addValidCommandParameters(DiskBalancer.MAXERROR, "Max errors to tolerate " +
        "between 2 disks");
    addValidCommandParameters(DiskBalancer.VERBOSE, "Run plan command in " +
        "verbose mode.");
    addValidCommandParameters(DiskBalancer.PLAN, "Plan Command");
  }

  /**
   * Runs the plan command. This command can be run with various options like
   * <p>
   * -plan -node IP -plan -node hostName -plan -node DatanodeUUID
   *
   * @param cmd - CommandLine
   */
  @Override
  public void execute(CommandLine cmd) throws Exception {
    LOG.debug("Processing Plan Command.");
    Preconditions.checkState(cmd.hasOption(DiskBalancer.PLAN));
    verifyCommandOptions(DiskBalancer.PLAN, cmd);

    if (cmd.getOptionValue(DiskBalancer.PLAN) == null) {
      throw new IllegalArgumentException("A node name is required to create a" +
          " plan.");
    }

    if (cmd.hasOption(DiskBalancer.BANDWIDTH)) {
      this.bandwidth = Integer.parseInt(cmd.getOptionValue(DiskBalancer
          .BANDWIDTH));
    }

    if (cmd.hasOption(DiskBalancer.MAXERROR)) {
      this.maxError = Integer.parseInt(cmd.getOptionValue(DiskBalancer
          .MAXERROR));
    }

    readClusterInfo(cmd);
    String output = null;
    if (cmd.hasOption(DiskBalancer.OUTFILE)) {
      output = cmd.getOptionValue(DiskBalancer.OUTFILE);
    }
    setOutputPath(output);

    // -plan nodename is the command line argument.
    DiskBalancerDataNode node = getNode(cmd.getOptionValue(DiskBalancer.PLAN));
    if (node == null) {
      throw new IllegalArgumentException("Unable to find the specified node. " +
          cmd.getOptionValue(DiskBalancer.PLAN));
    }
    this.thresholdPercentage = getThresholdPercentage(cmd);

    LOG.debug("threshold Percentage is {}", this.thresholdPercentage);
    setNodesToProcess(node);
    populatePathNames(node);

    NodePlan plan = null;
    List<NodePlan> plans = getCluster().computePlan(this.thresholdPercentage);
    setPlanParams(plans);

    if (plans.size() > 0) {
      plan = plans.get(0);
    }


    try (FSDataOutputStream beforeStream = create(String.format(
        DiskBalancer.BEFORE_TEMPLATE,
        cmd.getOptionValue(DiskBalancer.PLAN)))) {
      beforeStream.write(getCluster().toJson()
          .getBytes(StandardCharsets.UTF_8));
    }

    if (plan != null && plan.getVolumeSetPlans().size() > 0) {
      LOG.info("Writing plan to : {}", getOutputPath());
      try (FSDataOutputStream planStream = create(String.format(
          DiskBalancer.PLAN_TEMPLATE,
          cmd.getOptionValue(DiskBalancer.PLAN)))) {
        planStream.write(plan.toJson().getBytes(StandardCharsets.UTF_8));
      }
    } else {
      LOG.info("No plan generated. DiskBalancing not needed for node: {} " +
              "threshold used: {}", cmd.getOptionValue(DiskBalancer.PLAN),
          this.thresholdPercentage);
    }

    if (cmd.hasOption(DiskBalancer.VERBOSE) && plans.size() > 0) {
      printToScreen(plans);
    }
  }

  /**
   * Reads the Physical path of the disks we are balancing. This is needed to
   * make the disk balancer human friendly and not used in balancing.
   *
   * @param node - Disk Balancer Node.
   */
  private void populatePathNames(DiskBalancerDataNode node) throws IOException {
    String dnAddress = node.getDataNodeIP() + ":" + node.getDataNodePort();
    ClientDatanodeProtocol dnClient = getDataNodeProxy(dnAddress);
    String volumeNameJson = dnClient.getDiskBalancerSetting(
        DiskBalancerConstants.DISKBALANCER_VOLUME_NAME);
    ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    Map<String, String> volumeMap =
        mapper.readValue(volumeNameJson, HashMap.class);
    for (DiskBalancerVolumeSet set : node.getVolumeSets().values()) {
      for (DiskBalancerVolume vol : set.getVolumes()) {
        if (volumeMap.containsKey(vol.getUuid())) {
          vol.setPath(volumeMap.get(vol.getUuid()));
        }
      }
    }
  }

  /**
   * Gets extended help for this command.
   */
  @Override
  public void printHelp() {
    String header = "Creates a plan that describes how much data should be " +
        "moved between disks.\n\n";

    String footer = "\nPlan command creates a set of steps that represent a " +
        "planned data move. A plan file can be executed on a data node, which" +
        " will balance the data.";

    HelpFormatter helpFormatter = new HelpFormatter();
    helpFormatter.printHelp("hdfs diskbalancer -plan " +
        "<hostname> [options]", header, DiskBalancer.getPlanOptions(), footer);
  }

  /**
   * Get Threshold for planning purpose.
   *
   * @param cmd - Command Line Argument.
   * @return double
   */
  private double getThresholdPercentage(CommandLine cmd) {
    Double value = 0.0;
    if (cmd.hasOption(DiskBalancer.THRESHOLD)) {
      value = Double.parseDouble(cmd.getOptionValue(DiskBalancer.THRESHOLD));
    }

    if ((value <= 0.0) || (value > 100.0)) {
      value = getConf().getDouble(
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THRUPUT,
          DFSConfigKeys.DFS_DISK_BALANCER_MAX_DISK_THRUPUT_DEFAULT);
    }
    return value;
  }

  /**
   * Prints a quick summary of the plan to screen.
   *
   * @param plans - List of NodePlans.
   */
  static private void printToScreen(List<NodePlan> plans) {
    System.out.println("\nPlan :\n");
    System.out.println(StringUtils.repeat("=", 80));

    System.out.println(
        StringUtils.center("Source Disk", 30) +
            StringUtils.center("Dest.Disk", 30) +
            StringUtils.center("Size", 10) +
            StringUtils.center("Type", 10));

    for (NodePlan plan : plans) {
      for (Step step : plan.getVolumeSetPlans()) {
        System.out.println(String.format("%s %s %s %s",
            StringUtils.center(step.getSourceVolume().getPath(), 30),
            StringUtils.center(step.getDestinationVolume().getPath(), 30),
            StringUtils.center(step.getSizeString(step.getBytesToMove()), 10),
            StringUtils.center(step.getDestinationVolume().getStorageType(),
                10)));
      }
    }

    System.out.println(StringUtils.repeat("=", 80));
  }

  /**
   * Sets user specified plan parameters.
   *
   * @param plans - list of plans.
   */
  private void setPlanParams(List<NodePlan> plans) {
    for (NodePlan plan : plans) {
      for (Step step : plan.getVolumeSetPlans()) {
        if (this.bandwidth > 0) {
          LOG.debug("Setting bandwidth to {}", this.bandwidth);
          step.setBandwidth(this.bandwidth);
        }
        if (this.maxError > 0) {
          LOG.debug("Setting max error to {}", this.maxError);
          step.setMaxDiskErrors(this.maxError);
        }
      }
    }
  }
}
