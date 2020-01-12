package eu.the5zig.reconnect.util;

import java.util.ArrayList;
import java.util.List;

public abstract class CmdUtil {
	
	public static List<String> copyPartialMatches(List<String> toSearch, String what) {
		List<String> lst = new ArrayList<String>();
		what = what.toLowerCase();
		for (String s : toSearch) {
			if (s.toLowerCase().contains(what)) {
				lst.add(s);
			}
		}
		return lst;
	}

}
