// Copyright © 2015-2023 Andy Goryachev <andy@goryachev.com>
package goryachev.notebook.js.nb;
import goryachev.notebook.SymjaNotebookApp;
import goryachev.notebook.symja.JsObjects;
import goryachev.notebook.symja.SymjaEngine;
import goryachev.notebook.util.Arg;
import goryachev.notebook.util.Doc;
import goryachev.notebook.util.InlineHelp;


@Doc("provides operations with the notebook application:")
public class NB
{
	public NB()
	{
	}
	
	
	@Doc("displays an object in the code output section")
	@Arg("x")
	public void display(Object x)
	{
		SymjaEngine.get().display(x);
	}
	
	
	@Doc("stores a string value in the notebook storage")
	@Arg({"key", "value"})
	public void setValue(String key, String val)
	{
		SymjaNotebookApp.getStorage().setValue(key, val);
	}
	
	
	@Doc("returns a string value from the notebook storage")
	@Arg({"key"})
	public String getValue(String key)
	{
		return SymjaNotebookApp.getStorage().getValue(key);
	}
	
	
	public String toString()
	{
		return getHelp().toString();
	}
	
	
	public InlineHelp getHelp()
	{
		return InlineHelp.create(JsObjects.NB, getClass());
	}
}
