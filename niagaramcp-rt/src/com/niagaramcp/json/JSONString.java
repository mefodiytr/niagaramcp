package com.niagaramcp.json;

/**
 * Interface for objects that know how to serialise themselves as a JSON string.
 */
public interface JSONString {
   /**
    * @return well-formed JSON representation of this object
    */
   String toJSONString();
}
