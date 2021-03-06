/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.persistence;

import java.util.Collection;
import com.vividsolutions.jts.geom.Polygon;


/**
 * <p>
 * Default implementation of {@link IObsFilter} returning null on all filter
 * predicates. It is meant be used as a base to implement your own filter and
 * unlike {@link IObsFilter} doesn't require implementing all methods.  
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since May 9, 2015
 */
public class ObsFilter extends DataFilter implements IObsFilter
{
        
    public ObsFilter(String recordType)
    {
        super(recordType);
    }


    @Override
    public double[] getResultTimeRange()
    {
        return null;
    }


    @Override
    public Collection<String> getFoiIDs()
    {
        return null;
    }


    @Override
    public Polygon getRoi()
    {
        return null;
    }
}
