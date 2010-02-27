package org.jetbrains.plugins.groovy.dsl.toplevel.scopes

/**
 * @author ilyas
 */
abstract class Scope {}

class ClassScope extends Scope {
  private final myNamePattern

  ClassScope(Map args) {
    myNamePattern = args && args.name ? args.name : /.*/
  }

  def getName() {
    myNamePattern
  }
}

class ClosureScope extends Scope {
  private final boolean isArg
  private final boolean isTransparent

  ClosureScope(Map args) {
    isArg == args && args.isArgument ? args.isArgument : false
    isTransparent = args && args.transparent ? args.transparent : false
  }

  def isArg() {
    isArg
  }

  def transparent() {
    isTransparent
  }
}

class ScriptScope extends Scope {
  final String namePattern

  ScriptScope(Map args) {
    namePattern = args && args.name ? args.name : /.*/
  }

}
