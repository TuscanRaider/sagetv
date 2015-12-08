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
package sage.media.rss;

/**
 *
 * @author Narflex
 */
public class RSSEnclosure extends RSSObject
{

  /** Creates a new instance of RSSEnclosure */
  public RSSEnclosure(String url, String type, String length)
  {
    this.setUrl(url);
    this.setType(type);
    this.setLength(length);
  }

  public String toDebugString()
  {
    return "Enclosure[url=" + url + " type=" + type + " length=" + length + "]";
  }

  private String url;
  private String type;
  private String length;

  public String getUrl()
  {
    return url;
  }

  public void setUrl(String url)
  {
    this.url = url;
  }

  public String getType()
  {
    return type;
  }

  public void setType(String type)
  {
    this.type = type;
  }

  public String getLength()
  {
    return length;
  }

  public void setLength(String length)
  {
    this.length = length;
  }

}
