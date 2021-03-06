/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.process.trupulse;

import java.util.Arrays;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.processing.DataSourceConfig;
import org.sensorhub.api.processing.ProcessException;
import org.sensorhub.api.sensor.ISensorDataInterface;
import org.sensorhub.api.sensor.SensorDataEvent;
import org.sensorhub.impl.process.geoloc.GeoTransforms;
import org.sensorhub.impl.process.geoloc.NadirPointing;
import org.sensorhub.impl.processing.AbstractStreamProcess;
import org.sensorhub.impl.sensor.trupulse.TruPulseConfig;
import org.sensorhub.impl.sensor.trupulse.TruPulseOutput;
import org.sensorhub.impl.sensor.trupulse.TruPulseSensor;
import org.sensorhub.vecmath.Mat3d;
import org.sensorhub.vecmath.Vect3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.process.DataQueue;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;


/**
 * <p>
 * Example process for geolocating the range finder target knowing the 
 * device position either as provided by user, or from an associated GPS.
 * This works because TruPulse range finder also provides the azimuth
 * and inclination/elevation of the beam, in addition to the range.
 * </p>
 *
 * <p>Copyright (c) 2015 Sensia Software LLC</p>
 * @author Alexandre Robin <alex.robin@sensiasoftware.com>
 * @since June 21, 2015
 */
public class TargetGeolocProcess extends AbstractStreamProcess<TargetGeolocConfig>
{
    protected static final Logger log = LoggerFactory.getLogger(TargetGeolocProcess.class);
    
    protected TargetGeolocOutput targetLocOutput;
    protected GeoTransforms geoConv = new GeoTransforms();
    protected NadirPointing nadirPointing = new NadirPointing();
    
    protected boolean lastSensorPosSet = false;
    protected Vect3d lastSensorPosEcef = new Vect3d();
    protected Vect3d lla = new Vect3d();
    protected Mat3d ecefRot = new Mat3d();
    
    protected DataRecord sensorLocInput;
    protected DataComponent rangeMeasInput;    
    protected DataQueue sensorLocQueue;
    protected DataQueue rangeMeasQueue;
    
    
    @Override
    public void init(TargetGeolocConfig config) throws SensorHubException
    {
        this.config = config;
        
        // initialize with fixed pos if set
        if (config.fixedPosLLA != null)
        {
            double[] pos = config.fixedPosLLA; // lat-lon-alt in degrees
            
            try
            {
                lla.set(Math.toRadians(pos[1]), Math.toRadians(pos[0]), pos[2]);
                geoConv.LLAtoECEF(lla, lastSensorPosEcef);
                lastSensorPosSet = true;
            }
            catch (Exception e)
            {
                throw new SensorHubException("Invalid sensor position: " + Arrays.toString(pos));
            }
        }
        
        // create inputs
        SWEHelper fac = new SWEHelper();   
        
        sensorLocInput = fac.newDataRecord();
        sensorLocInput.setName("sensorLocation");
        sensorLocInput.addField("time", fac.newTimeStampIsoUTC());
        sensorLocInput.addField("loc", fac.newLocationVectorLLA(SWEConstants.DEF_SENSOR_LOC));
        inputs.put(sensorLocInput.getName(), sensorLocInput);
        
        TruPulseOutput sensorOutput = new TruPulseOutput(null);
        sensorOutput.init();
        rangeMeasInput = sensorOutput.getRecordDescription();
        inputs.put(rangeMeasInput.getName(), rangeMeasInput);
        
        // create outputs
        targetLocOutput = new TargetGeolocOutput(this);
        addOutput(targetLocOutput);
        
        super.init(config);
    }
    
    
    @Override
    protected void connectInput(String inputName, String dataPath, DataQueue inputQueue) throws Exception
    {        
        super.connectInput(inputName, dataPath, inputQueue);
        
        if (inputName.equals(sensorLocInput.getName()))
            sensorLocQueue = inputQueue;
        
        else if (inputName.equals(rangeMeasInput.getName()))
            rangeMeasQueue = inputQueue;
    }
    
    
    @Override
    protected void process(DataEvent lastEvent) throws ProcessException
    {
        try
        {
            if (sensorLocQueue != null && sensorLocQueue.isDataAvailable())
            {
                // data received is LLA in degrees
                DataBlock dataBlk = sensorLocQueue.get();
                double lat = dataBlk.getDoubleValue(1);
                double lon = dataBlk.getDoubleValue(2);
                double alt = dataBlk.getDoubleValue(3);
                log.trace("Last GPS pos = [{},{},{}]" , lat, lon, alt);
                
                // convert to radians and then ECEF
                lla.y = Math.toRadians(lat);
                lla.x = Math.toRadians(lon);
                lla.z = alt;
                geoConv.LLAtoECEF(lla, lastSensorPosEcef);
                lastSensorPosSet = true;
            }
            
            else if (lastSensorPosSet && rangeMeasQueue.isDataAvailable())
            {
                DataBlock dataBlk = rangeMeasQueue.get();
                double time = dataBlk.getDoubleValue(0);
                double range = dataBlk.getDoubleValue(2);
                double az = dataBlk.getDoubleValue(3);
                double inc = dataBlk.getDoubleValue(4);
                log.debug("TruPulse meas: range={}, az={}, inc={}" , range, az, inc);
                if (Double.isNaN(range))
                    return;
                
                // express LOS in ENU frame                
                Vect3d los = new Vect3d(0.0, range, 0.0);
                los.rotateX(Math.toRadians(inc));
                los.rotateZ(Math.toRadians(-az));
                
                // transform to ECEF frame
                nadirPointing.getRotationMatrixENUToECEF(lastSensorPosEcef, ecefRot);
                los.rotate(ecefRot);
                los.add(lastSensorPosEcef);
                
                // convert target location back to LLA
                geoConv.ECEFtoLLA(los, lla);
                targetLocOutput.sendLocation(time, Math.toDegrees(lla.y), Math.toDegrees(lla.x), lla.z);
            }
        }
        catch (InterruptedException e)
        {
        }
    }
    
    
    @Override
    public boolean isPauseSupported()
    {
        return false;
    }

    
    @Override
    public boolean isCompatibleDataSource(DataSourceConfig dataSource)
    {
        return true;
    }
    
    
    public static void main(String[] args) throws Exception
    {
        TargetGeolocProcess p = new TargetGeolocProcess();
        TargetGeolocConfig processConf = new TargetGeolocConfig();
        processConf.fixedPosLLA = new double[] {45.0, 0.0, 0.0};
        p.init(processConf);
        p.sensorLocQueue = new DataQueue();
        p.rangeMeasQueue = new DataQueue();
        
        TruPulseSensor sensor = new TruPulseSensor();
        TruPulseConfig sensorConf = new TruPulseConfig();
        sensor.init(sensorConf);
        ISensorDataInterface sensorOutput = sensor.getAllOutputs().values().iterator().next();
        DataComponent outputDef = sensorOutput.getRecordDescription();
                
        IStreamingDataInterface processOutput = p.getAllOutputs().values().iterator().next();
        IEventListener l = new IEventListener() {
            public void handleEvent(Event<?> e)
            {
                DataBlock data = ((DataEvent)e).getRecords()[0];
                double lat = data.getDoubleValue(1);
                double lon = data.getDoubleValue(2);
                double alt = data.getDoubleValue(3);
                System.out.println("Target Loc = " + lat + "," + lon + "," + alt);
            }
        };
        processOutput.registerListener(l);
        
        DataBlock dataBlk = outputDef.createDataBlock();
        long now = System.currentTimeMillis();
        double range = 1000.0;
        double az = 90.0;
        double inc = 45.0;
        dataBlk.setDoubleValue(0, now / 1000.);
        dataBlk.setDoubleValue(2, range);
        dataBlk.setDoubleValue(3, az);
        dataBlk.setDoubleValue(4, inc);
        p.rangeMeasQueue.add(dataBlk);
        p.process(new SensorDataEvent(now, sensorOutput, dataBlk));
    }
}
