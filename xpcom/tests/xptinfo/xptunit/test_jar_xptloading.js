const xptiITestInterface_IID = "{13c9fc33-40e6-44dc-81e2-21e0dd41f232}";

var id = Components.interfaces.xptiITestInterface;
do_check_true(id != undefined, "xptiITestInterface not registered");

var id2 = Components.interfacesById[xptiITestInterface_IID];
do_check_true(id === id2, "xptiITestInterface info doesn't match");
