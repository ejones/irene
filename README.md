Irene Build Tool
================

Irene simplifies the development of high-performance web apps. It provides project scaffolds, compilation, bundling, and development reloading. It covers HTML, CSS, JavaScript and templating. It embodies common [Best Practices](http://developer.yahoo.com/performance/rules.html) for fast sites, and more.

Here's the rundown:

* **dependencies for a file are declared inside it**: there are no build files, manifests or scripts involved
* **dependencies are bundled together** resulting in a single, cacheable download for the browser
* **templates are compiled to JavaScript** which is in turn minified and can be bundled
* **no JavaScript frameworks are required**, only a small set of utilites for templating, if templates are used

It incorporates the following tools to do this:

* **CSS** files are processed with [Google Closure Stylesheets](http://code.google.com/p/closure-stylesheets/)
* **templates (.soy)** are processed with [Google Closure (Soy) Templates](https://developers.google.com/closure/templates/)
* **JavaScript** files are processed with the [Google Closure Compiler](https://developers.google.com/closure/compiler/)


## Quick Start

Irene runs on the JVM. Pick up the [latest jar](https://github.com/downloads/ejones/irene/irene-latest.jar) and run:

    java -jar irene-latest.jar create hello

This is a shorthand for "create basic hello", i.e., create a project called "hello" with the "basic" template. You will get a project that looks like:

    +-hello/
      +-hello.css
      +-hello.soy
      +-hello.js
      +-hello.html

Poke around if you want, and then run the following to build:

    java -jar irene-latest.jar hello

Then check out the result by opening ``hello/hello.min.html``.


## When does it make sense?

Irene bundles the dependencies of an HTML page or JavaScript library together into a single download. This makes the most sense for rich web apps where a page is downloaded once and cached for several future loads. The action happens in data API calls originating from JavaScript (i.e., AJAX or Comet heavy).

Here's a typical resource flow. This is what the first request from the browser looks like:

      |                                                         |
    B |   GET /static/main.min.html                             | S
    R | ------------------------------------------------------> | E
    O | <------------------------------------------------------ | R
    W |   200 OK                                                | V
    S |   ...                                                   | E
    E |   <html>(all resources req'd for main.html)</html>      | R
    R |                                                         |
      |                                                         |
      |   GET /app/load_data                                    |
      | ------------------------------------------------------> |
      | <------------------------------------------------------ |
      |   200 OK                                                |
      |   ...                                                   |
      |   { "data": ... }                                       |
 
And here's each subsequent request, if the user doesn't hit "refresh", etc.:

      |                                                         |
    B |   GET /static/main.min.html                             | S
    R | -----------.                                            | E
    O | <----------'                                            | R
    W |   (from cache)                                          | V
    S |   ...                                                   | E
    E |   <html>(all resources req'd for main.html)</html>      | R
    R |                                                         |
      |                                                         |
      |   GET /app/load_data                                    |
      | ------------------------------------------------------> |
      | <------------------------------------------------------ |
      |   200 OK                                                |
      |   ...                                                   |
      |   { "data": ... }                                       |
      |                                                         |

That is, for subsequent requests, the server only sees the (small) API calls.


## How does it work?

Irene picks up file dependencies in two ways depending depending on the filetype. For HTML, it treats any `<script>` or `<link>` tag that points to a valid local file as a compilable dependency. For the other types (.css, .js, .soy), it looks for the [JSDoc](http://code.google.com/p/jsdoc-toolkit/) tag "requires", when it is surrounded by quotes. Here's an example:

```js
/**
 * @fileoverview Drives the user profile.
 * @requires "util.js"
 * @requires "views.soy"
 */
```

In this way, HTML may depend on .css and .js files, CSS on other .css, JavaScript on .js or .soy, and Soy on other .soy.

When you give `irene-latest.jar` a directory as its argument (or none, indicating the current directory), it recurses on the files in that directory and subdirectories, processing files that match:

* *.html
* main.js
* DIR.js

Where DIR would be the file's parent directory.

The outputs will appear alongside the originals with ".min." inserted before their extensions. For example, the basic scaffold we created above will look like this after compilation:

    +-hello/
      +-hello.css
      +-hello.min.css
      +-hello.soy
      +-hello.min.soy
      +-hello.js
      +-hello.min.js
      +-hello.html
      +-hello.min.html

The intermediate ".min." files are kept around to speed up compilation (resources are only recompiled when they've been modified later than a target). It's best to add "*.min.*" to your .gitignore or .hgignore files.


### But what about large external libraries?

As a compromise, any dependencies that contain ".min.", like "jquery-latest.min.js", are ignored.


## Other goodies

### Last Modified

Irene will expose a variable called "$LAST_MODIFIED" in JavaScript, which is the modification time of the currently executing file (JavaScript or HTML page). Using this, your app server can, if you choose, instruct the browser to permanently cache any files containing JavaScript, and on the *first* API call that the JavaScript makes, it will send it the current modification time of the file that's supposed to be executing. If this timestamp is later than "$LAST_MODIFIED", the JavaScript can force a reload using `window.refresh(true)`.

### Development Recompiler

Using the "develop" command, Irene will stick around after compilation and continually recompile whenever source files or dependencies change. In our example above, we would run:

    java -jar irene-latest.jar develop hello


## Contributing

After cloning the [Git repository](https://github.com/ejones/irene), you will need to use [sbt](https://github.com/harrah/xsbt/wiki) to work on the project.


Copyright (C) 2012 by Evan Jones. Licensed under the MIT License - see LICENSE.txt for details.
