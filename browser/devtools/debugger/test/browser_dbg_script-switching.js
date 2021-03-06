/* vim:set ts=2 sw=2 sts=2 et: */
/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

/**
 * Make sure that switching the displayed script in the UI works as advertised.
 */

const TAB_URL = EXAMPLE_URL + "browser_dbg_script-switching.html";

let tempScope = {};
Cu.import("resource:///modules/source-editor.jsm", tempScope);
let SourceEditor = tempScope.SourceEditor;

var gPane = null;
var gTab = null;
var gDebuggee = null;
var gDebugger = null;
var gScripts = null;

function test()
{
  let scriptShown = false;
  let framesAdded = false;

  debug_tab_pane(TAB_URL, function(aTab, aDebuggee, aPane) {
    gTab = aTab;
    gDebuggee = aDebuggee;
    gPane = aPane;
    gDebugger = gPane.debuggerWindow;

    gDebugger.DebuggerController.activeThread.addOneTimeListener("framesadded", function() {
      framesAdded = true;
      runTest();
    });

    gDebuggee.firstCall();
  });

  window.addEventListener("Debugger:ScriptShown", function _onEvent(aEvent) {
    let url = aEvent.detail.url;
    if (url.indexOf("-02.js") != -1) {
      scriptShown = true;
      window.removeEventListener(aEvent.type, _onEvent);
      runTest();
    }
  });

  function runTest()
  {
    if (scriptShown && framesAdded) {
      Services.tm.currentThread.dispatch({ run: testScriptsDisplay }, 0);
    }
  }
}

function testScriptsDisplay() {
  gScripts = gDebugger.DebuggerView.Scripts._scripts;

  is(gDebugger.DebuggerController.activeThread.state, "paused",
    "Should only be getting stack frames while paused.");

  is(gScripts.itemCount, 2, "Found the expected number of scripts.");

  for (let i = 0; i < gScripts.itemCount; i++) {
    info("label: " + i + " " + gScripts.getItemAtIndex(i).getAttribute("label"));
  }

  let label1 = "test-script-switching-01.js";
  let label2 = "test-script-switching-02.js";

  ok(gDebugger.DebuggerView.Scripts.contains(EXAMPLE_URL +
    label1), "First script url is incorrect.");
  ok(gDebugger.DebuggerView.Scripts.contains(EXAMPLE_URL +
    label2), "Second script url is incorrect.");

  ok(gDebugger.DebuggerView.Scripts.containsLabel(
    label1), "First script label is incorrect.");
  ok(gDebugger.DebuggerView.Scripts.containsLabel(
    label2), "Second script label is incorrect.");

  dump("Debugger editor text:\n" + gDebugger.editor.getText() + "\n");

  ok(gDebugger.editor.getText().search(/debugger/) != -1,
    "The correct script was loaded initially.");

  is(gDebugger.editor.getDebugLocation(), 5,
     "editor debugger location is correct.");

  window.addEventListener("Debugger:ScriptShown", function _onEvent(aEvent) {
    let url = aEvent.detail.url;
    if (url.indexOf("-01.js") != -1) {
      window.removeEventListener(aEvent.type, _onEvent);
      testSwitchPaused();
    }
  });

  gDebugger.DebuggerView.Scripts.selectScript(EXAMPLE_URL + label1);
}

function testSwitchPaused()
{
  dump("Debugger editor text:\n" + gDebugger.editor.getText() + "\n");

  ok(gDebugger.editor.getText().search(/debugger/) == -1,
    "The second script is no longer displayed.");

  ok(gDebugger.editor.getText().search(/firstCall/) != -1,
    "The first script is displayed.");

  is(gDebugger.editor.getDebugLocation(), -1,
     "editor debugger location has been cleared.");

  gDebugger.DebuggerController.activeThread.resume(function() {
    window.addEventListener("Debugger:ScriptShown", function _onEvent(aEvent) {
      let url = aEvent.detail.url;
      if (url.indexOf("-02.js") != -1) {
        window.removeEventListener(aEvent.type, _onEvent);
        testSwitchRunning();
      }
    });

    gDebugger.DebuggerView.Scripts.selectScript(EXAMPLE_URL +
                                                "test-script-switching-02.js");
  });
}

function testSwitchRunning()
{
  ok(gDebugger.editor.getText().search(/debugger/) != -1,
    "The second script is displayed again.");

  ok(gDebugger.editor.getText().search(/firstCall/) == -1,
    "The first script is no longer displayed.");

  is(gDebugger.editor.getDebugLocation(), -1,
     "editor debugger location is still -1.");

  closeDebuggerAndFinish(gTab);
}

registerCleanupFunction(function() {
  removeTab(gTab);
  gPane = null;
  gTab = null;
  gDebuggee = null;
  gDebugger = null;
});
