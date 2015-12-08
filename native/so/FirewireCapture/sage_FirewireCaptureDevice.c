/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/errno.h>
#include <unistd.h>
#include <features.h> 
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>
#include <getopt.h>
#include <errno.h>
#include <sys/time.h>
#include <math.h>
#include <linux/types.h>
#include <time.h>
#include <sys/timeb.h>
#include <sys/types.h>
#include <sys/un.h>
#include <libraw1394/raw1394.h>
#include <libavc1394/avc1394.h>
#include <libiec61883/iec61883.h>
#include <libraw1394/csr.h>

#include "sage_FirewireCaptureDevice.h"
#include "sage_EncodingException.h"

#define BUFFERSIZE 188*16

#define DEBUGFirewire

static void sysOutPrint(JNIEnv* env, const char* cstr, ...)
{
	jthrowable oldExcept = (*env)->ExceptionOccurred(env);
	if (oldExcept)
		(*env)->ExceptionClear(env);
    va_list args;
    va_start(args, cstr);
    char buf[1024];
    vsprintf(buf, cstr, args);
    va_end(args);
	jstring jstr = (*env)->NewStringUTF(env, buf);
	jclass cls = (jclass) (*env)->NewGlobalRef(env, (*env)->FindClass(env, "java/lang/System"));
	jfieldID outField = (*env)->GetStaticFieldID(env, cls, "out", "Ljava/io/PrintStream;");
	jmethodID printMeth = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/io/PrintStream"),
		"print", "(Ljava/lang/String;)V");
	jobject outObj = (*env)->GetStaticObjectField(env, cls, outField);
	(*env)->CallVoidMethod(env, outObj, printMeth, jstr);
	(*env)->DeleteLocalRef(env, jstr);
	if (oldExcept)
		(*env)->Throw(env, oldExcept);
}

typedef struct FirewireCaptureDev
{
	FILE* fd; // file to write the captured data to
	long circFileSize;
	char devName[256];
	unsigned char buf[BUFFERSIZE];
	long circWritePos;
	raw1394handle_t handle;
	octlet_t guid;
	int port;        
	int node;
	int oplug;
	int iplug;
	int bandwidth;
	int channel;
	octlet_t datalen;
	iec61883_mpeg2_t mpeg;
} FirewireCaptureDev;


static octlet_t get_guid(raw1394handle_t handle, nodeid_t node)
{
        quadlet_t       quadlet;
        octlet_t        offset;
        octlet_t    guid = 0;

        offset = CSR_REGISTER_BASE + CSR_CONFIG_ROM + 0x0C;
        raw1394_read(handle, node, offset, sizeof(quadlet_t), &quadlet);
        quadlet = htonl(quadlet);
        guid = quadlet;
        guid <<= 32;
        offset = CSR_REGISTER_BASE + CSR_CONFIG_ROM + 0x10;
        raw1394_read(handle, node, offset, sizeof(quadlet_t), &quadlet);
        quadlet = htonl(quadlet);
        guid += quadlet;

        return guid;
}

static int write_packet(unsigned char *data, int len, unsigned int dropped, void *callback_data)
{
	FirewireCaptureDev *CDev= (FirewireCaptureDev *) callback_data;
	int numbytes=len;                
	int bufSkip = 0;
	if (numbytes)
	{
		if (CDev->circFileSize)
		{
			if (CDev->circWritePos == CDev->circFileSize)
			{
				// Wrap it now
				fseek(CDev->fd, 0, 0);
				CDev->circWritePos = 0;
			}
			if (CDev->circWritePos + numbytes <= CDev->circFileSize)
			{
				// No wrapping this time
				if (fwrite(data + bufSkip, 1, numbytes, CDev->fd) == EOF)
				{
					return -1;
				}
				CDev->circWritePos += numbytes;
				}
			else
			{
				if (fwrite(data + bufSkip, 1, CDev->circFileSize - CDev->circWritePos, CDev->fd) == EOF)
				{
					return -1;
				}
				int remBytes = numbytes - (CDev->circFileSize - CDev->circWritePos);
				// Wrap it now
				fseek(CDev->fd, 0, 0);
				CDev->circWritePos = 0;
				if (fwrite(data + bufSkip + (numbytes - remBytes), 1, remBytes, CDev->fd) == EOF)
				{
					return -1;
				}
			}
		}
		else
		{
			if (fwrite(data + bufSkip, 1, numbytes, CDev->fd) == -1)
			{
				return -1;
			}
		}
	}
	CDev->datalen+=len;
	fflush(CDev->fd);
	return 0;
}

static void mpeg2_start_receive (JNIEnv* env, raw1394handle_t handle, FirewireCaptureDev *CDev, int channel)
{
	CDev->mpeg = iec61883_mpeg2_recv_init(handle, write_packet,
		(void *)CDev );
	if(CDev->mpeg)
	{
		if(iec61883_mpeg2_recv_start(CDev->mpeg, channel) == 0)
		{
			sysOutPrint(env,"Firewire receive start\n");
			return;
		}
		iec61883_mpeg2_close(CDev->mpeg);
		CDev->mpeg=NULL;
	}
	sysOutPrint(env,"Firewire couldn't start receive\n");
	return;
}

void throwEncodingException(JNIEnv* env, jint errCode)
{
	static jclass encExcClass = 0;
	
	static jmethodID encConstMeth = 0;
	if (!encExcClass)
	{
		encExcClass = (jclass) (*env)->NewGlobalRef(env, (*env)->FindClass(env, "sage/EncodingException"));
		encConstMeth = (*env)->GetMethodID(env, encExcClass, "<init>", "(II)V");
	}
	jthrowable throwie = (jthrowable) (*env)->NewObject(env, encExcClass, encConstMeth, errCode, (jint) errno);
	(*env)->Throw(env, throwie);
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    createEncoder0
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_sage_FirewireCaptureDevice_createEncoder0
  (JNIEnv *env, jobject jo, jstring jdevname)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire create Encoder.\n");
#endif
	struct raw1394_portinfo portinfo[16];
	int nports, port, i, device;
	quadlet_t guid_lo, guid_hi;
	FirewireCaptureDev *CDev = (FirewireCaptureDev *) malloc(sizeof(FirewireCaptureDev));
	const char* cdevname = (*env)->GetStringUTFChars(env, jdevname, NULL);
	strcpy(CDev->devName, cdevname);
	(*env)->ReleaseStringUTFChars(env, jdevname, cdevname);

#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire scanning in %s.\n", CDev->devName);
#endif

	if(sscanf(CDev->devName,"Firewire GUID %08x %08x", &guid_hi, &guid_lo) != 2)
	{
		throwEncodingException(env, __LINE__);
		free(CDev);
		return 0;
	}

	CDev->guid=guid_hi;
	CDev->guid<<=32;
	CDev->guid|=guid_lo;
        
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire GUID %08x %08x\n", (quadlet_t) (CDev->guid>>32), (quadlet_t) (CDev->guid & 0xffffffff));
	fflush(stdout);        
#endif

	if(!(CDev->handle = raw1394_new_handle())) 
	{
		throwEncodingException(env, __LINE__);
		free(CDev);
		return 0;
	}

	if((nports = raw1394_get_port_info(CDev->handle, portinfo, 16)) < 0)
	{
		throwEncodingException(env, __LINE__);
		raw1394_destroy_handle(CDev->handle);
		free(CDev);
		return 0;
	}

	CDev->node = -1;
	for(port = 0; port < nports; port++)
	{
		if(raw1394_set_port(CDev->handle, port) < 0) 
		{
			continue;
		}
    
		for(device = 0; device < raw1394_get_nodecount(CDev->handle); device++)
		{
			octlet_t guid = get_guid(CDev->handle, 0xffc0 | device);
			if(guid==CDev->guid)
			{
			CDev->node=0xffc0|device;
			CDev->port=port;
			}                                                                        
		}
	}
        
        if(CDev->node == -1)
	{
#ifdef DEBUGFirewire
		sysOutPrint(env, "Couldn't find GUID %08x %08x\n", (quadlet_t) (CDev->guid>>32), (quadlet_t) (CDev->guid & 0xffffffff));
#endif
		throwEncodingException(env, __LINE__);
		raw1394_destroy_handle(CDev->handle);
		free(CDev);
		return 0;
	}

	raw1394_set_port(CDev->handle, CDev->port); 
        
#ifdef DEBUGFirewire
	sysOutPrint(env, "Found GUID %08x %08x on port %d node %d\n", (quadlet_t) (CDev->guid>>32), 
		(quadlet_t) (CDev->guid & 0xffffffff), CDev->port, CDev->node);
#endif
	return (jlong) CDev;
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    setupEncoding0
 * Signature: (JLjava/lang/String;J)Z
 */
JNIEXPORT jboolean JNICALL Java_sage_FirewireCaptureDevice_setupEncoding0
  (JNIEnv *env, jobject jo, jlong ptr, jstring jfilename, jlong circSize)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire setup encoding.\n");
#endif
	if (ptr)
	{
		FirewireCaptureDev *CDev = (FirewireCaptureDev *) ptr;
		CDev->circFileSize = (long) circSize;

		const char* cfilename = (*env)->GetStringUTFChars(env, jfilename, NULL);
		CDev->fd = fopen(cfilename, "wb");
		CDev->datalen=0;
		(*env)->ReleaseStringUTFChars(env, jfilename, cfilename);
		if (!CDev->fd)
		{
			throwEncodingException(env, __LINE__);
			return JNI_FALSE;
		}
		CDev->oplug=-1;
		CDev->iplug=-1;
		CDev->bandwidth=-1;
		CDev->channel = iec61883_cmp_connect (CDev->handle, CDev->node, &CDev->oplug,
			raw1394_get_local_id (CDev->handle), &CDev->iplug, &CDev->bandwidth);
		mpeg2_start_receive(env, CDev->handle, CDev, CDev->channel);
		return JNI_TRUE;
	}
	return JNI_FALSE;
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    switchEncoding0
 * Signature: (JLjava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL Java_sage_FirewireCaptureDevice_switchEncoding0
  (JNIEnv *env, jobject jo, jlong ptr, jstring jfilename)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire switch encoding.\n");
#endif
	// Change the output file to be this new file
	if (ptr)
	{
		FirewireCaptureDev *CDev = (FirewireCaptureDev *) ptr;
		if (CDev->fd)
		{
			fclose(CDev->fd);
			CDev->fd = 0;
		}
		// Open up the file we're going to write to
		const char* cfilename = (*env)->GetStringUTFChars(env, jfilename, NULL);
		CDev->fd = fopen(cfilename, "wb");
		(*env)->ReleaseStringUTFChars(env, jfilename, cfilename);
		if (!CDev->fd)
		{
			throwEncodingException(env, __LINE__/*sage_EncodingException_FILESYSTEM*/);
			return JNI_FALSE;
		}
		return JNI_TRUE;
	}
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    closeEncoding0
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_sage_FirewireCaptureDevice_closeEncoding0
  (JNIEnv *env, jobject jo, jlong ptr)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire close encoding.\n");
#endif
	if (ptr)
	{
		FirewireCaptureDev *CDev = (FirewireCaptureDev *) ptr;
		if(CDev->mpeg)
		{
			iec61883_mpeg2_close(CDev->mpeg);
			CDev->mpeg=NULL;
		}
		if (CDev->fd)
		{
			fclose(CDev->fd);
			CDev->fd = 0;
		}
		iec61883_cmp_disconnect (CDev->handle, CDev->node, CDev->oplug,
			raw1394_get_local_id(CDev->handle), CDev->iplug,
			CDev->channel, CDev->bandwidth);
		return JNI_TRUE;
	}
	else
		return JNI_FALSE;
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    destroyEncoder0
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sage_FirewireCaptureDevice_destroyEncoder0
  (JNIEnv *env, jobject jo, jlong ptr)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire Destroy encoder.\n");
#endif
	if (ptr)
	{
		FirewireCaptureDev *CDev = (FirewireCaptureDev *) ptr;
		raw1394_destroy_handle(CDev->handle);
		free(CDev);
	}
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    eatEncoderData0
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_sage_FirewireCaptureDevice_eatEncoderData0
  (JNIEnv *env, jobject jo, jlong ptr)
{
	if (ptr)
	{
		FirewireCaptureDev *CDev = (FirewireCaptureDev *) ptr;

		int readMore = 1;
		int numbytes = 0;
		while (readMore) // for now we don't loop
		{
			if(CDev->mpeg)
			{
				int fd = raw1394_get_fd (CDev->handle);
				struct timeval tv;
				fd_set rfds;
				int result = 0;
		
				FD_ZERO (&rfds);
				FD_SET (fd, &rfds);
				tv.tv_sec = 0;
				tv.tv_usec = 20000;

				if (select (fd + 1, &rfds, NULL, NULL, &tv) > 0)
					result = raw1394_loop_iterate (CDev->handle);
				numbytes=CDev->datalen;
				CDev->datalen=0;
			}
			else
			{
				usleep(100000);
			}
			readMore = 0;
		}

#ifdef DEBUGFirewire
        sysOutPrint(env, "eatEncoderData0 %d\n", numbytes);
#endif
		return numbytes;
	}
	return 0;
}


//#define AVC1394_SUBUNIT_TYPE_PANEL (9 << 19)
#define AVC1394_PASSTHROUGH_COMMAND 0x000007C00   /* PASS THROUGH subunit command */
    
/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    setChannel0
 * Signature: (JLjava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL Java_sage_FirewireCaptureDevice_setChannel0
  (JNIEnv *env, jobject jo, jlong ptr, jstring jchan)
{
	const char* chann = (*env)->GetStringUTFChars(env, jchan, NULL);
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire setChannel0\n");
#endif
	if(!ptr) return JNI_FALSE;
	FirewireCaptureDev *CDev = (FirewireCaptureDev *) ptr;
        
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire set channel %s.\n", chann);
#endif

	int channel = atoi(chann);
	(*env)->ReleaseStringUTFChars(env, jchan, chann);

    if(channel==0) return JNI_TRUE;

	{
		quadlet_t request[3];
		quadlet_t *response;
        
        request[0] = AVC1394_CTYPE_CONTROL | AVC1394_SUBUNIT_TYPE_UNIT | 
            AVC1394_SUBUNIT_ID_IGNORE | AVC1394_COMMAND_POWER | AVC1394_CMD_OPERAND_POWER_ON;
		// power up the unit        
		response = avc1394_transaction_block(CDev->handle, CDev->node&0x3F, request, 1, 1);
		if(response!=NULL)
		sysOutPrint(env, "power response %08X\n",response[0]);
	}


	{
		quadlet_t request[3];
		quadlet_t *response;
        
		request[0] = AVC1394_CTYPE_CONTROL | AVC1394_SUBUNIT_TYPE_PANEL | 
			AVC1394_SUBUNIT_ID_0 | AVC1394_PASSTHROUGH_COMMAND | 
			0x67; // Press
		request[1] = 0x040000FF | (channel << 8);
		request[2] = 0xFF000000;
		// set the channel
		response = avc1394_transaction_block(CDev->handle, CDev->node&0x3F, request, 3, 1);
		if(response!=NULL)
		sysOutPrint(env, "67 push response %08X\n",response[0]);
        
		request[0] = AVC1394_CTYPE_CONTROL | AVC1394_SUBUNIT_TYPE_PANEL | 
			AVC1394_SUBUNIT_ID_0 | AVC1394_PASSTHROUGH_COMMAND | 
			0x67|0x80; // Release
		request[1] = 0x040000FF | (channel << 8);
		request[2] = 0xFF000000;
		// set the channel
		response = avc1394_transaction_block(CDev->handle, CDev->node&0x3F, request, 3, 1);
		if(response!=NULL)
		sysOutPrint(env, "67 release response %08X\n",response[0]);
	}
	return JNI_TRUE;
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    setInput0
 * Signature: (JIILjava/lang/String;II)Z
 */
JNIEXPORT jboolean JNICALL Java_sage_FirewireCaptureDevice_setInput0
  (JNIEnv *env, jobject jo, jlong ptr, jint inputType, jint inputIndex,
	jstring tunerMode, jint countryCode, jint videoFormatCode)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire SetInput\n");
#endif
	if(!ptr) return JNI_FALSE;

	FirewireCaptureDev *CDev = (FirewireCaptureDev *) ptr;
	const char* cform = (*env)->GetStringUTFChars(env, tunerMode, NULL);
	int isCable = (strcmp("Cable", cform) == 0);
	int isHrc = (strcmp("HRC", cform) == 0);
	(*env)->ReleaseStringUTFChars(env, tunerMode, cform);
	// Set the frequency array based on the standard
	return JNI_TRUE;
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    setEncoding0
 * Signature: (JLjava/lang/String;Ljava/util/Map;)Z
 */
JNIEXPORT jboolean JNICALL Java_sage_FirewireCaptureDevice_setEncoding0
  (JNIEnv *env, jobject jo, jlong ptr, jstring jencName, jobject encodePropsMap)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire SetEncoding\n");
#endif
	return JNI_TRUE;
}

/*
 * Class:     sage_FirewireCaptureDevice
 * Method:    updateColors0
 * Signature: (JIIIII)[I
 */
JNIEXPORT jintArray JNICALL Java_sage_FirewireCaptureDevice_updateColors0
  (JNIEnv *env, jobject jo, jlong ptr, jint brightness, jint contrast, jint huey, 
	jint saturation, jint sharpness)
{
#ifdef DEBUGFirewire
	sysOutPrint(env, "Firewire updateColors0 b=%d c=%d h=%d s=%d\n", brightness, contrast, huey, saturation);
#endif
	jint retColors[5];
	retColors[0]=0; retColors[1]=0; retColors[2]=0; retColors[3]=0; retColors[4]=0;
	jintArray rv = (*env)->NewIntArray(env, 5);
	(*env)->SetIntArrayRegion(env, rv, 0, 5, retColors);
	return rv;
}


JNIEXPORT jstring JNICALL Java_sage_LinuxFirewireCaptureManager_ListFirewireNodes0(JNIEnv *env, jobject jo)
{
	raw1394handle_t handle;
	int device;
	int nports, port, i;
	struct raw1394_portinfo portinfo[16];
	jstring result;
	char *firewirestring=NULL;
        
	if(!(handle = raw1394_new_handle())) 
	{
		return (*env)->NewStringUTF(env, "");
	}

	if((nports = raw1394_get_port_info(handle, portinfo, 16)) < 0)
	{
		raw1394_destroy_handle(handle);
		return (*env)->NewStringUTF(env, "");
	}

	for(port = 0; port < nports; port++)
	{
		if(raw1394_set_port(handle, port) < 0) 
		{
			continue;
		}
    
		for(device = 0; device < raw1394_get_nodecount(handle); device++)
		{
			octlet_t guid = get_guid(handle, 0xffc0 | device);
			if(firewirestring != NULL)
			{
				firewirestring=(char *) realloc(firewirestring, strlen(firewirestring)+1+32);
				sprintf(firewirestring+strlen(firewirestring),
					",Firewire GUID %08x %08x", (quadlet_t) (guid>>32), (quadlet_t) (guid & 0xffffffff));
			}
			else
			{
				firewirestring=(char *) malloc(1+31);
				firewirestring[0]=0; // for strlen in sprintf
				sprintf(firewirestring+strlen(firewirestring),
					"Firewire GUID %08x %08x", (quadlet_t) (guid>>32), (quadlet_t) (guid & 0xffffffff));
			}
		}
	}
	if(firewirestring!=NULL)
    {
        result = (*env)->NewStringUTF(env, firewirestring);
        free(firewirestring);
    }
    else
    {
        result = (*env)->NewStringUTF(env, "");
    }
	raw1394_destroy_handle(handle);
	return result;
}

JNIEXPORT jboolean JNICALL Java_sage_LinuxFirewireCaptureManager_AvailableFirewireDevice0(JNIEnv *env, jobject jo, jstring jdevname)
{
	raw1394handle_t handle;
	int device;
	int nports, port, i;
	struct raw1394_portinfo portinfo[16];
	jstring result;
	quadlet_t guid_lo, guid_hi;
	octlet_t devguid;
	const char* cdevname = (*env)->GetStringUTFChars(env, jdevname, NULL);

	if(sscanf(cdevname,"Firewire GUID %08x %08x", &guid_hi, &guid_lo) != 2)
	{
		throwEncodingException(env, __LINE__);
		(*env)->ReleaseStringUTFChars(env, jdevname, cdevname);
		return 0;
	}

	(*env)->ReleaseStringUTFChars(env, jdevname, cdevname);

	devguid=guid_hi;
	devguid<<=32;
	devguid|=guid_lo;

#ifdef DEBUGFirewire
	sysOutPrint(env, "Searching for firewire GUID %08x %08x\n", (quadlet_t) (devguid>>32), (quadlet_t) (devguid & 0xffffffff));
#endif

	if(!(handle = raw1394_new_handle())) 
	{
		return JNI_FALSE;
	}

	if((nports = raw1394_get_port_info(handle, portinfo, 16)) < 0)
	{
		raw1394_destroy_handle(handle);
		return JNI_FALSE;
	}

	for(port = 0; port < nports; port++)
	{
		if(raw1394_set_port(handle, port) < 0) 
		{
			continue;
		}
    
		for(device = 0; device < raw1394_get_nodecount(handle); device++)
		{
			octlet_t guid = get_guid(handle, 0xffc0 | device);
			if(guid==devguid)
			{
				raw1394_destroy_handle(handle);
#ifdef DEBUGFirewire
				sysOutPrint(env, "Found firewire GUID %08x %08x\n", 
					(quadlet_t) (devguid>>32), (quadlet_t) (devguid & 0xffffffff));
#endif
				return JNI_TRUE;                        
			}
		}
	}                                        
	raw1394_destroy_handle(handle);
	return JNI_FALSE;
}
