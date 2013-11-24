/***************************************************************************
* Copyright (c) 2013 VMware, Inc. All Rights Reserved.
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
***************************************************************************/

package com.vmware.vhadoop.api.vhm;

/* Represents actions which can be invoked on the Rabbit MQ subsystem */
public interface QueueClient 
{
   public class CannotConnectException extends Exception {
      private static final long serialVersionUID = -5779586054546770967L;

      public CannotConnectException(String msg, Throwable t) {
         super(msg, t);
      }
   }
   
   void sendMessage(byte[] data) throws CannotConnectException;
}
