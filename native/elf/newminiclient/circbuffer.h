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
#ifndef __CIRBUFFER_H__
#define __CIRBUFFER_H__

typedef struct
{
    unsigned char *data;
    int size;
    int start;
    int datalen;
} circBuffer;

int createCircBuffer(circBuffer *buf, int len);
int resetCircBuffer(circBuffer *buf);
int freespaceCircBuffer(circBuffer *buf);
int usedspaceCircBuffer(circBuffer *buf);
int addCircBuffer(circBuffer *buf, unsigned char *data, int len);
int dropCircBuffer(circBuffer *buf, int len);
int getCircBuffer(circBuffer *buf, unsigned char *data, int len);
#define getByteCircBuffer(buf, position) \
    ((buf)->data[((buf)->start+position >= (buf)->size) ? (buf)->start+position-(buf)->size : (buf)->start+position])
#define setByteCircBuffer(buf, position,val) \
    ((buf)->data[((buf)->start+position >= (buf)->size) ? (buf)->start+position-(buf)->size : (buf)->start+position]=val)
int getCircBuffer2(circBuffer *inbuf, circBuffer *outbuf, int len);

#endif // __CIRBUFFER_H__
