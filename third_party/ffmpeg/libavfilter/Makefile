include ../config.mak

CFLAGS+=-I$(SRC_PATH)/libavformat -I$(SRC_PATH)/libavcodec -I$(SRC_PATH)/libswscale

OBJS = allfilters.o \
       avfilter.o \
       defaults.o \
       formats.o \

#OBJS-$(CONFIG_VF_XXXX)       += vf_xxx.o

HEADERS = avfilter.h

EXTRALIBS := -L$(BUILD_ROOT)/libavcodec -lavcodec$(BUILDSUF) \
             -L$(BUILD_ROOT)/libswscale -lswscale$(BUILDSUF) \
             -L$(BUILD_ROOT)/libavutil  -lavutil$(BUILDSUF) $(EXTRALIBS)

ifeq ($(CONFIG_AVFILTER_LAVF),yes)
EXTRALIBS := -L$(BUILD_ROOT)/libavformat -lavformat$(BUILDSUF) $(EXTRALIBS)
endif

NAME=avfilter
LIBVERSION=$(LAVFILTERVERSION)
LIBMAJOR=$(LAVFILTERMAJOR)

include ../common.mak
