package io.jenkins.plugins.bee;

import java.util.ArrayList;
import java.util.List;


public class SwarmConnector {

    private List<SwarmConnectorData> swarmList;

    public List<SwarmConnectorData> getSwarmList() {
        if(swarmList == null){
            swarmList = new ArrayList<SwarmConnectorData>();
        }
        return swarmList;
    }

    public void setSwarmList(List<SwarmConnectorData> swarmList) {
        this.swarmList = swarmList;
    }
}
