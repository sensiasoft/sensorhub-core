/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are Copyright (C) 2014 Sensia Software LLC.
 All Rights Reserved.
 
 Contributor(s): 
    Alexandre Robin <alex.robin@sensiasoftware.com>
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.tools;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map.Entry;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.TextEncoding;
import org.sensorhub.api.persistence.DataKey;
import org.sensorhub.api.persistence.IDataFilter;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.ITimeSeriesDataStore;
import org.sensorhub.impl.persistence.perst.BasicStorageConfig;
import org.sensorhub.impl.persistence.perst.BasicStorageImpl;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWECommonUtils;
import org.vast.swe.SWEHelper;
import org.vast.xml.DOMHelper;
import org.vast.xml.XMLImplFinder;
import org.w3c.dom.Element;


public class DbExport
{
    
    
    
    public static void main(String[] args) throws Exception
    {
        args = new String[] {
            "/home/alex/Documents/Projects/Workspace_OGC/sensorhub/sensorhub-test/commute_2015-02-16/urn:android:device:060693280a28e015.dat"
        };
        
        if (args.length < 1)
        {
            System.out.println("Usage: DbExport storage_path");
            System.exit(1);
        }
        
        // open storage
        String dbPath = args[0];
        BasicStorageConfig dbConf = new BasicStorageConfig();
        dbConf.enabled = true;
        dbConf.memoryCacheSize = 1024;
        dbConf.storagePath = dbPath;
        BasicStorageImpl db = new BasicStorageImpl();
        db.init(dbConf);
        db.start();
        
        // write everything to output file
        try
        {
            File outputFile = new File(dbPath + ".export.metadata");
            if (outputFile.exists())
                throw new IOException("DB export file already exist: " + outputFile);
            
            // prepare XML output
            DOMHelper dom = new DOMHelper("db_export");
            SMLUtils smlUtils = new SMLUtils();
            SWECommonUtils sweUtils = new SWECommonUtils();
            XMLImplFinder.setStaxOutputFactory(new com.ctc.wstx.stax.WstxOutputFactory());
            
            // export all SensorML descriptions
            for (AbstractProcess process: db.getDataSourceDescriptionHistory(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
            {
                Element smlElt = dom.addElement('+' + DbConstants.SECTION_SENSORML);
                smlElt.appendChild(smlUtils.writeProcess(dom, process));
                System.out.println("Exported SensorML description " + process.getId());
            }
            
            // export all datastores
            final double[] timePeriod = new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
            IDataFilter recordFilter = new IDataFilter() {
                public double[] getTimeStampRange() { return timePeriod; }
                public String getProducerID() { return null; }
            };
            
            for (Entry<String, ? extends ITimeSeriesDataStore<IDataFilter>> entry: db.getDataStores().entrySet())
            {
                String dataStoreName = entry.getKey();
                ITimeSeriesDataStore<IDataFilter> dataStore = entry.getValue();
                DataComponent recordStruct = dataStore.getRecordDescription();
                DataEncoding recordEncoding = dataStore.getRecommendedEncoding();
                
                Element dataStoreElt = dom.addElement('+' + DbConstants.SECTION_DATASTORE);
                dom.setAttributeValue(dataStoreElt, "name", dataStoreName);
                
                // add data description
                Element recordStructElt = dom.addElement(dataStoreElt, DbConstants.SECTION_RECORD_STRUCTURE);
                recordStructElt.appendChild(sweUtils.writeComponent(dom, recordStruct));
                Element recordEncElt = dom.addElement(dataStoreElt, DbConstants.SECTION_RECORD_ENCODING);
                recordEncElt.appendChild(sweUtils.writeEncoding(dom, recordEncoding));
                System.out.println("Exported data store " + dataStoreName);
                                
                // write records to separate binary file
                DataStreamWriter recordWriter = null;
                try
                {
                    File dataFile = new File(outputFile.getParent(), dataStoreName + ".export.data");
                    if (dataFile.exists())
                        throw new IOException("Data store export file already exist: " + dataFile);
                    OutputStream recordOutput = new BufferedOutputStream(new FileOutputStream(dataFile));
                    DataOutputStream dos = new DataOutputStream(recordOutput);
                    
                    // prepare record writer
                    recordWriter = SWEHelper.createDataWriter(recordEncoding);
                    recordWriter.setDataComponents(recordStruct);
                    recordWriter.setOutput(recordOutput);
                    
                    // write all records
                    int recordCount = 0;
                    Iterator<? extends IDataRecord<DataKey>> it = dataStore.getRecordIterator(recordFilter);
                    while (it.hasNext())
                    {
                        IDataRecord<DataKey> rec = it.next();
                        dos.writeDouble(rec.getKey().timeStamp);
                        if (rec.getKey().producerID == null)
                            dos.writeUTF(DbConstants.KEY_NULL_PRODUCER);
                        else
                            dos.writeUTF(rec.getKey().producerID);
                        recordWriter.write(rec.getData());
                        recordWriter.flush();
                        if (recordEncoding instanceof TextEncoding)
                            dos.write('\n');
                        recordCount++;
                    }
                    
                    System.out.println("Exported " + recordCount + " records");
                }
                finally
                {
                    if (recordWriter != null)
                        recordWriter.close();
                }
            }
            
            // write out the whole metadata file
            OutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
            dom.serialize(dom.getRootElement(), os, true);
        }
        finally
        {

        }
    }

}
