#!/usr/bin/env ruby
# encoding: utf-8

# Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved.
# This code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 1.0
# GNU General Public License version 2
# GNU Lesser General Public License version 2.1

# A workflow tool for TruffleRuby development

# Recommended: function jt { ruby tool/jt.rb "$@"; }

require 'fileutils'
require 'json'
require 'timeout'
require 'yaml'
require 'open3'
require 'rbconfig'
require 'pathname'

TRUFFLERUBY_DIR = File.expand_path('../..', File.realpath(__FILE__))
M2_REPO         = File.expand_path('~/.m2/repository')
MRI_TEST_CEXT_DIR = "#{TRUFFLERUBY_DIR}/test/mri/tests/cext/c"

TRUFFLERUBY_GEM_TEST_PACK_VERSION = 3

JDEBUG_PORT = 51819
JDEBUG = "-J-agentlib:jdwp=transport=dt_socket,server=y,address=#{JDEBUG_PORT},suspend=y"
JEXCEPTION = "-Xexceptions.print_uncaught_java=true"
METRICS_REPS = Integer(ENV["TRUFFLERUBY_METRICS_REPS"] || 10)

UNAME = `uname`.chomp
MAC = UNAME == 'Darwin'
LINUX = UNAME == 'Linux'

SO = MAC ? 'dylib' : 'so'

# Expand GEM_HOME relative to cwd so it cannot be misinterpreted later.
ENV['GEM_HOME'] = File.expand_path(ENV['GEM_HOME']) if ENV['GEM_HOME']

if MAC && !ENV['OPENSSL_PREFIX']
  ENV['OPENSSL_PREFIX'] = '/usr/local/opt/openssl'
end

# wait for sub-processes to handle the interrupt
trap(:INT) {}

module Utilities
  def self.truffle_version
    File.foreach("#{TRUFFLERUBY_DIR}/truffle/pom.rb") do |line|
      if /'truffle\.version' => '((?:\d+\.\d+|\h+)(?:-SNAPSHOT)?)'/ =~ line
        break $1
      end
    end
  end

  def self.truffle_release?
    !truffle_version.include?('SNAPSHOT')
  end

  def self.find_graal_javacmd_and_options
    graalvm = ENV['GRAALVM_BIN']
    jvmci = ENV['JVMCI_BIN']
    graal_home = ENV['GRAAL_HOME']

    raise "More than one of GRAALVM_BIN, JVMCI_BIN or GRAAL_HOME defined!" if [graalvm, jvmci, graal_home].compact.count > 1

    if graalvm
      javacmd = File.expand_path(graalvm, TRUFFLERUBY_DIR)
      vm_args = []
      options = []
    elsif jvmci
      javacmd = File.expand_path(jvmci, TRUFFLERUBY_DIR)
      jvmci_graal_home = ENV['JVMCI_GRAAL_HOME']
      raise "Also set JVMCI_GRAAL_HOME if you set JVMCI_BIN" unless jvmci_graal_home
      jvmci_graal_home = File.expand_path(jvmci_graal_home, TRUFFLERUBY_DIR)
      vm_args = [
        '-d64',
        '-XX:+UnlockExperimentalVMOptions',
        '-XX:+EnableJVMCI',
        '--add-exports=java.base/jdk.internal.module=com.oracle.graal.graal_core',
        "--module-path=#{jvmci_graal_home}/../truffle/mxbuild/modules/com.oracle.truffle.truffle_api.jar:#{jvmci_graal_home}/mxbuild/modules/com.oracle.graal.graal_core.jar"
      ]
      options = ['--no-bootclasspath']
    elsif graal_home || auto_graal_home = find_auto_graal_home
      if graal_home
        graal_home = File.expand_path(graal_home, TRUFFLERUBY_DIR)
      else
        graal_home = auto_graal_home
      end
      output, _ = ShellUtils.mx('-v', '-p', graal_home, 'vm', '-version', :err => :out, capture: true)
      command_line = output.lines.select { |line| line.include? '-version' }
      if command_line.size == 1
        command_line = command_line[0]
      else
        $stderr.puts "Error in mx for setting up Graal:"
        $stderr.puts output
        abort
      end
      vm_args = command_line.split
      vm_args.pop # Drop "-version"
      javacmd = vm_args.shift
      options = []
    else
      raise 'set one of GRAALVM_BIN or GRAAL_HOME in order to use Graal'
    end
    [javacmd, vm_args.map { |arg| "-J#{arg}" } + options]
  end

  def self.find_auto_graal_home
    sibling_compiler = File.expand_path('../graal/compiler', TRUFFLERUBY_DIR)
    return nil unless Dir.exist?(sibling_compiler)
    return nil unless File.exist?("#{sibling_compiler}/mxbuild/dists/graal-compiler.jar")
    return nil if Dir.exist?("#{TRUFFLERUBY_DIR}/mx.imports/binary/truffle")
    sibling_compiler
  end

  def self.which(binary)
    ENV["PATH"].split(File::PATH_SEPARATOR).each do |dir|
      path = "#{dir}/#{binary}"
      return path if File.executable? path
    end
    nil
  end

  def self.find_mx
    if mx = which('mx')
      mx
    else
      mx_repo = find_or_clone_repo("https://github.com/graalvm/mx.git")
      "#{mx_repo}/mx"
    end
  end

  def self.find_graal_js
    jar = ENV['GRAAL_JS_JAR']
    return jar if jar
    raise "couldn't find trufflejs.jar - download GraalVM as described in https://github.com/jruby/jruby/wiki/Downloading-GraalVM and find it in there"
  end

  def self.find_sl
    jar = ENV['SL_JAR']
    return jar if jar
    raise "couldn't find truffle-sl.jar - build Truffle and find it in there"
  end

  def self.find_launcher
    if ENV['RUBY_BIN']
      ENV['RUBY_BIN']
    else
      "#{TRUFFLERUBY_DIR}/bin/truffleruby"
    end
  end

  def self.find_repo(name)
    [TRUFFLERUBY_DIR, "#{TRUFFLERUBY_DIR}/.."].each do |dir|
      found = Dir.glob("#{dir}/#{name}*").sort.first
      return File.expand_path(found) if found
    end
    raise "Can't find the #{name} repo - clone it into the repository directory or its parent"
  end

  def self.find_or_clone_repo(url)
    name = File.basename url, '.git'
    path = File.expand_path("../#{name}", TRUFFLERUBY_DIR)
    unless Dir.exist? path
      ShellUtils.sh "git", "clone", url, "../#{name}"
    end
    path
  end

  def self.find_benchmark(benchmark)
    if File.exist?(benchmark)
      benchmark
    else
      File.join(TRUFFLERUBY_DIR, 'bench', benchmark)
    end
  end

  def self.find_gem(name)
    ["#{TRUFFLERUBY_DIR}/lib/ruby/gems/shared/gems"].each do |dir|
      found = Dir.glob("#{dir}/#{name}*").sort.first
      return File.expand_path(found) if found
    end

    [TRUFFLERUBY_DIR, "#{TRUFFLERUBY_DIR}/.."].each do |dir|
      found = Dir.glob("#{dir}/#{name}").sort.first
      return File.expand_path(found) if found
    end
    raise "Can't find the #{name} gem - gem install it in this repository, or put it in the repository directory or its parent"
  end

  def self.git_branch
    @git_branch ||= `GIT_DIR="#{TRUFFLERUBY_DIR}/.git" git rev-parse --abbrev-ref HEAD`.strip
  end

  def self.igv_running?
    `ps ax`.include?('idealgraphvisualizer')
  end

  def self.ensure_igv_running
    abort "I can't see IGV running - go to your checkout of Graal and run 'mx igv' in a separate shell, then run this command again" unless igv_running?
  end

  def self.no_gem_vars_env
    {
      'TRUFFLERUBY_RESILIENT_GEM_HOME' => nil,
      'GEM_HOME' => nil,
      'GEM_PATH' => nil,
      'GEM_ROOT' => nil,
    }
  end

  def self.human_size(bytes)
    if bytes < 1024
      "#{bytes} B"
    elsif bytes < 1000**2
      "#{(bytes/1024.0).round(2)} KB"
    elsif bytes < 1000**3
      "#{(bytes/1024.0**2).round(2)} MB"
    elsif bytes < 1000**4
      "#{(bytes/1024.0**3).round(2)} GB"
    else
      "#{(bytes/1024.0**4).round(2)} TB"
    end
  end

  def self.log(tty_message, full_message)
    if STDERR.tty?
      STDERR.print tty_message unless tty_message.nil?
    else
      STDERR.print full_message unless full_message.nil?
    end
  end

end

module ShellUtils
  module_function

  def system_timeout(timeout, *args)
    begin
      pid = Process.spawn(*args)
    rescue SystemCallError
      return nil
    end

    begin
      Timeout.timeout timeout do
        Process.waitpid pid
        $?.success?
      end
    rescue Timeout::Error
      Process.kill('TERM', pid)
      Process.waitpid pid
      nil
    end
  end

  def raw_sh(*args)
    options = args.last.is_a?(Hash) ? args.last : {}
    continue_on_failure = options.delete :continue_on_failure
    use_exec = options.delete :use_exec
    timeout = options.delete :timeout
    capture = options.delete :capture

    unless options.delete :no_print_cmd
      STDERR.puts "$ #{printable_cmd(args)}"
    end

    if use_exec
      result = exec(*args)
    elsif timeout
      result = system_timeout(timeout, *args)
    elsif capture
      out, err, status = Open3.capture3(*args)
      result = status.success?
    else
      result = system(*args)
    end

    if result
      if capture
        [out, err]
      else
        true
      end
    elsif continue_on_failure
      false
    else
      status = $? unless capture
      $stderr.puts "FAILED (#{status}): #{printable_cmd(args)}"

      if capture
        $stderr.puts out
        $stderr.puts err
      end

      if status && status.exitstatus
        exit status.exitstatus
      else
        exit 1
      end
    end
  end

  def printable_cmd(args)
    env = {}
    if Hash === args.first
      env, *args = args
    end
    if Hash === args.last && args.last.empty?
      *args, options = args
    end
    env = env.map { |k,v| "#{k}=#{shellescape(v)}" }.join(' ')
    args = args.map { |a| shellescape(a) }.join(' ')
    env.empty? ? args : "#{env} #{args}"
  end

  def shellescape(str)
    return str unless str.is_a?(String)
    if str.include?(' ')
      if str.include?("'")
        require 'shellwords'
        Shellwords.escape(str)
      else
        "'#{str}'"
      end
    else
      str
    end
  end

  def replace_env_vars(string, env = ENV)
    string.gsub(/\$([A-Z_]+)/) {
      var = $1
      abort "You need to set $#{var}" unless env[var]
      env[var]
    }
  end

  def sh(*args)
    Dir.chdir(TRUFFLERUBY_DIR) do
      raw_sh(*args)
    end
  end

  def mx(*args)
    raw_sh Utilities.find_mx, *args
  end

  def mx_sulong(*args)
    mx '--dynamicimports', 'sulong', *args
  end

  def mspec(command, *args)
    env_vars = {}
    if command.is_a?(Hash)
      env_vars = command
      command, *args = args
    end

    mspec_args = ['spec/mspec/bin/mspec', command, '--config', 'spec/truffle.mspec', *args]

    if i = args.index('-t')
      launcher = args[i+1]
      flags = args.select { |arg| arg.start_with?('-T') }.map { |arg| arg[2..-1] }
      sh env_vars, launcher, *flags, *mspec_args, use_exec: true
    else
      ruby env_vars, *mspec_args
    end
  end

  def newer?(input, output)
    return true unless File.exist? output
    File.mtime(input) > File.mtime(output)
  end
end

module Commands
  include ShellUtils

  def help
    puts <<-TXT.gsub(/^#{' '*6}/, '')
      jt build [options]                             build
          parser                                     build the parser
          options                                    build the options
      jt build_stats [--json] <attribute>            prints attribute's value from build process (e.g., binary size)
      jt clean                                       clean
      jt rebuild                                     clean, sforceimports, and build
      jt dis <file>                                  finds the bc file in the project, disassembles, and returns new filename
      jt run [options] args...                       run JRuby with args
          --graal         use Graal (set either GRAALVM_BIN, JVMCI_BIN or GRAAL_HOME, or have graal built as a sibling)
              --stress    stress the compiler (compile immediately, foreground compilation, compilation exceptions are fatal)
          --js            add Graal.js to the classpath (set GRAAL_JS_JAR)
          --asm           show assembly (implies --graal)
          --server        run an instrumentation server on port 8080
          --igv           make sure IGV is running and dump Graal graphs after partial escape (implies --graal)
              --full      show all phases, not just up to the Truffle partial escape
          --infopoints    show source location for each node in IGV
          --fg            disable background compilation
          --trace         show compilation information on stdout
          --jdebug        run a JDWP debug server on #{JDEBUG_PORT}
          --jexception[s] print java exceptions
          --exec          use exec rather than system
          --no-print-cmd  don\'t print the command
      jt e 14 + 2                                    evaluate an expression
      jt puts 14 + 2                                 evaluate and print an expression
      jt cextc directory clang-args                  compile the C extension in directory, with optional extra clang arguments
      jt test                                        run all mri tests, specs and integration tests
      jt test tck                                    run the Truffle Compatibility Kit tests
      jt test mri                                    run mri tests
          --syslog        runs syslog tests
          --openssl       runs openssl tests
          --aot           use AOT TruffleRuby image (set AOT_BIN)
          --graal         use Graal (set either GRAALVM_BIN, JVMCI_BIN or GRAAL_HOME, or have graal built as a sibling)
      jt test specs                                  run all specs
      jt test specs fast                             run all specs except sub-processes, GC, sleep, ...
      jt test spec/ruby/language                     run specs in this directory
      jt test spec/ruby/language/while_spec.rb       run specs in this file
      jt test compiler                               run compiler tests (uses the same logic as --graal to find Graal)
      jt test integration                            runs all integration tests
      jt test integration [TESTS]                    runs the given integration tests
      jt test bundle [--no-sulong]                   tests using bundler
      jt test gems                                   tests using gems
      jt test ecosystem [TESTS]                      tests using the wider ecosystem such as bundler, Rails, etc
      jt test cexts [--no-openssl]                   run C extension tests (set GEM_HOME)
      jt test report :language                       build a report on language specs
                     :core                               (results go into test/target/mspec-html-report)
                     :library
      jt gem-test-pack                               check that the gem test pack is downloaded, or download it for you, and print the path
      jt rubocop [rubocop options]                   run rubocop rules (using ruby available in the environment)
      jt tag spec/ruby/language                      tag failing specs in this directory
      jt tag spec/ruby/language/while_spec.rb        tag failing specs in this file
      jt tag all spec/ruby/language                  tag all specs in this file, without running them
      jt untag spec/ruby/language                    untag passing specs in this directory
      jt untag spec/ruby/language/while_spec.rb      untag passing specs in this file
      jt mspec ...                                   run MSpec with the TruffleRuby configuration and custom arguments
      jt metrics alloc [--json] ...                  how much memory is allocated running a program (use -Xclassic to test normal JRuby on this metric and others)
      jt metrics instructions ...                    how many CPU instructions are used to run a program
      jt metrics minheap ...                         what is the smallest heap you can use to run an application
      jt metrics time ...                            how long does it take to run a command, broken down into different phases
      jt benchmark [options] args...                 run benchmark-interface (implies --graal)
          --no-graal              don't imply --graal
          JT_BENCHMARK_RUBY=ruby  benchmark some other Ruby, like MRI
          note that to run most MRI benchmarks, you should translate them first with normal Ruby and cache the result, such as
              benchmark bench/mri/bm_vm1_not.rb --cache
              jt benchmark bench/mri/bm_vm1_not.rb --use-cache
      jt where repos ...                            find these repositories
      jt next                                       tell you what to work on next (give you a random core library spec)
      jt pr [pr_number]                             pushes GitHub's PR to bitbucket to let CI run under github/pr/<number> name
                                                    if the pr_number is not supplied current HEAD is used to find a PR which contains it

      you can also put build or rebuild in front of any command

      recognised environment variables:

        RUBY_BIN                                     The TruffleRuby executable to use (normally just bin/truffleruby)
        GRAALVM_BIN                                  GraalVM executable (java command)
        GRAAL_HOME                                   Directory where there is a built checkout of the Graal compiler (make sure mx is on your path)
        JVMCI_BIN                                    JVMCI-enabled (so JDK 9 EA build) java command (aslo set JVMCI_GRAAL_HOME)
        JVMCI_GRAAL_HOME                             Like GRAAL_HOME, but only used for the JARs to run with JVMCI_BIN
        GRAAL_JS_JAR                                 The location of trufflejs.jar
        SL_JAR                                       The location of truffle-sl.jar
        OPENSSL_PREFIX                               Where to find OpenSSL headers and libraries
        AOT_BIN                                      TruffleRuby/SVM executable
    TXT
  end

  def mx(*args)
    super(*args)
  end

  def build(*options)
    project = options.first
    case project
    when 'parser'
      jay = Utilities.find_or_clone_repo('https://github.com/jruby/jay.git')
      raw_sh 'make', chdir: "#{jay}/src"
      ENV['PATH'] = "#{jay}/src:#{ENV['PATH']}"
      sh 'bash', 'tool/generate_parser'
      yytables = 'src/main/java/org/truffleruby/parser/parser/YyTables.java'
      File.write(yytables, File.read(yytables).gsub('package org.jruby.parser;', 'package org.truffleruby.parser.parser;'))
    when 'options'
      sh 'tool/generate-options.rb'
    when nil
      mx 'sforceimports'
      mx 'build', '--force-javac', '--warning-as-error'
    else
      raise ArgumentError, project
    end
  end

  def clean
    mx 'clean'
  end

  def dis(file)
    dis = `which llvm-dis-3.8 llvm-dis 2>/dev/null`.lines.first.chomp
    file = `find #{TRUFFLERUBY_DIR} -name "#{file}"`.lines.first.chomp
    raise ArgumentError, "file not found:`#{file}`" if file.empty?
    sh dis, file
    puts Pathname(file).sub_ext('.ll')
  end

  def rebuild
    clean
    mx 'sforceimports'
    build
  end

  def run(*args)
    env_vars = args.first.is_a?(Hash) ? args.shift : {}
    options = args.last.is_a?(Hash) ? args.pop : {}

    jruby_args = []

    {
      '--asm' => '--graal',
      '--stress' => '--graal',
      '--igv' => '--graal',
      '--trace' => '--graal',
    }.each_pair do |arg, dep|
      args.unshift dep if args.include?(arg)
    end

    unless args.delete('--no-core-load-path')
      jruby_args << "-Xcore.load_path=#{TRUFFLERUBY_DIR}/src/main/ruby"
    end

    if args.delete('--graal')
      if ENV["RUBY_BIN"]
        # Assume that Graal is automatically set up if RUBY_BIN is set.
        # This will also warn if it's not.
      else
        javacmd, javacmd_options = Utilities.find_graal_javacmd_and_options
        env_vars["JAVACMD"] = javacmd
        jruby_args.push(*javacmd_options)
      end
    else
      jruby_args << '-Xgraal.warn_unless=false'
    end

    if args.delete('--stress')
      jruby_args << '-J-Dgraal.TruffleCompileImmediately=true'
      jruby_args << '-J-Dgraal.TruffleBackgroundCompilation=false'
      jruby_args << '-J-Dgraal.TruffleCompilationExceptionsAreFatal=true'
    end

    if args.delete('--js')
      jruby_args << '-J-cp'
      jruby_args << Utilities.find_graal_js
    end

    if args.delete('--asm')
      jruby_args += %w[-J-XX:+UnlockDiagnosticVMOptions -J-XX:CompileCommand=print,*::callRoot]
    end

    if args.delete('--jdebug')
      jruby_args << JDEBUG
    end

    if args.delete('--jexception') || args.delete('--jexceptions')
      jruby_args << JEXCEPTION
    end

    if args.delete('--server')
      jruby_args += %w[-Xinstrumentation_server_port=8080]
    end

    if args.delete('--profile')
      v = Utilities.truffle_version
      jruby_args << "-J-Xbootclasspath/a:#{M2_REPO}/com/oracle/truffle/truffle-debug/#{v}/truffle-debug-#{v}.jar"
      jruby_args << "-J-Dtruffle.profiling.enabled=true"
    end

    if args.delete('--igv')
      Utilities.ensure_igv_running
      if args.delete('--full')
        jruby_args << "-J-Dgraal.Dump=:2"
      else
        jruby_args << "-J-Dgraal.Dump=TruffleTree,PartialEscape:2"
      end
      jruby_args << "-J-Dgraal.PrintBackendCFG=false"
    end

    if args.delete('--infopoints')
      jruby_args << "-J-XX:+UnlockDiagnosticVMOptions" << "-J-XX:+DebugNonSafepoints"
      jruby_args << "-J-Dgraal.TruffleEnableInfopoints=true"
    end

    if args.delete('--fg')
      jruby_args << "-J-Dgraal.TruffleBackgroundCompilation=false"
    end

    if args.delete('--trace')
      jruby_args << "-J-Dgraal.TraceTruffleCompilation=true"
    end

    if args.delete('--no-print-cmd')
      options[:no_print_cmd] = true
    end

    if args.delete('--exec')
      options[:use_exec] = true
    end

    ruby_bin = if args.delete('--aot')
                 verify_aot_bin!
                 ENV['AOT_BIN']
               else
                 Utilities.find_launcher
               end

    raw_sh env_vars, ruby_bin, *jruby_args, *args, options
  end

  # Same as #run but uses exec()
  def ruby(*args)
    run(*args, '--exec')
  end

  def e(*args)
    run '-e', args.join(' ')
  end

  def command_puts(*args)
    e 'puts begin', *args, 'end'
  end

  def command_p(*args)
    e 'p begin', *args, 'end'
  end

  def cextc(cext_dir, *clang_opts)
    cext_dir = File.expand_path(cext_dir)
    name = File.basename(cext_dir)
    ext_dir = "#{cext_dir}/ext/#{name}"
    target = "#{cext_dir}/lib/#{name}/#{name}.su"
    compile_cext(name, ext_dir, target, *clang_opts)
  end

  def compile_cext(name, ext_dir, target, *clang_opts)
    extconf = "#{ext_dir}/extconf.rb"
    raise "#{extconf} does not exist" unless File.exist?(extconf)

    # Make sure ruby.su is built
    raw_sh "make", chdir: "src/main/c"

    Dir.chdir(ext_dir) do
      STDERR.puts "in #{ext_dir} ..."
      run('-rmkmf', "extconf.rb") # -rmkmf is required for C ext tests
      raw_sh("make")
      FileUtils::Verbose.cp("#{name}.su", target) if target
    end
  end

  def pr(*args)
    out, _err   = sh 'git', 'remote', capture: true
    remotes     = out.split
    remote_urls = remotes.map do |remote|
      out, _err = sh 'git', 'config', '--get', "remote.#{remote}.url", capture: true
      [remote, out.chomp!]
    end

    upstream = remote_urls.find { |r, u| u.include? 'graalvm/truffleruby' }.first
    bb       = remote_urls.find { |r, u| u.include? 'ol-bitbucket' }.first

    # Fetch PRs on GitHub
    fetch = "+refs/pull/*/head:refs/remotes/#{upstream}/pr/*"
    out, _err = sh 'git', 'config', '--get-all', "remote.#{upstream}.fetch", capture: true
    sh 'git', 'config', '--add', "remote.#{upstream}.fetch", fetch unless out.include? fetch
    sh 'git', 'fetch', upstream

    pr_number = args.first
    if pr_number
      github_pr_branch = "#{upstream}/pr/#{pr_number}"
    else
      github_pr_branch = begin
        out, _err = sh 'git', 'branch', '-r', '--contains', 'HEAD', capture: true
        candidate = out.lines.find { |l| l.strip.start_with? "#{upstream}/pr/" }
        candidate && candidate.strip.chomp
      end

      unless github_pr_branch
        puts 'Could not find HEAD in any of the GitHub pull-requests.'
        exit 1
      end

      pr_number = github_pr_branch.split('/').last
    end

    sh 'git', 'push', '--force', bb, "#{github_pr_branch}:refs/heads/github/pr/#{pr_number}"
  end

  def test(*args)
    path, *rest = args

    case path
    when nil
      ENV['HAS_REDIS'] = 'true'
      %w[bundle compiler cexts integration gems ecosystem specs tck mri].each do |kind|
        jt('test', kind)
      end
    when 'bundle' then test_bundle(*rest)
    when 'compiler' then test_compiler(*rest)
    when 'cexts' then test_cexts(*rest)
    when 'report' then test_report(*rest)
    when 'integration' then test_integration(*rest)
    when 'gems' then test_gems(*rest)
    when 'ecosystem' then test_ecosystem(*rest)
    when 'specs' then test_specs('run', *rest)
    when 'tck' then test_tck(*rest)
    when 'mri' then test_mri(*rest)
    else
      if File.expand_path(path, TRUFFLERUBY_DIR).start_with?("#{TRUFFLERUBY_DIR}/test")
        test_mri(*args)
      else
        test_specs('run', *args)
      end
    end
  end

  def jt(*args)
    sh RbConfig.ruby, 'tool/jt.rb', *args
  end
  private :jt

  def test_mri(*args)
    if args.delete('--openssl')
      include_pattern = "#{TRUFFLERUBY_DIR}/test/mri/tests/openssl/test_*.rb"
      exclude_file = "#{TRUFFLERUBY_DIR}/test/mri/openssl.exclude"
    elsif args.delete('--syslog')
      include_pattern = ["#{TRUFFLERUBY_DIR}/test/mri/tests/test_syslog.rb",
                         "#{TRUFFLERUBY_DIR}/test/mri/tests/syslog/test_syslog_logger.rb"]
      exclude_file = nil
    elsif args.delete('--cext')
      include_pattern = "#{TRUFFLERUBY_DIR}/test/mri/tests/cext/ruby/**/test_*.rb"
      exclude_file = "#{TRUFFLERUBY_DIR}/test/mri/cext.exclude"
    elsif args.all? { |a| a.start_with?('-') }
      include_pattern = "#{TRUFFLERUBY_DIR}/test/mri/tests/**/test_*.rb"
      exclude_file = "#{TRUFFLERUBY_DIR}/test/mri/standard.exclude"
    else
      args, files_to_run = args.partition { |a| a.start_with?('-') }
    end

    unless files_to_run
      prefix = "#{TRUFFLERUBY_DIR}/test/mri/tests/"

      include_files = Dir.glob(include_pattern).map { |f|
        raise unless f.start_with?(prefix)
        f[prefix.size..-1]
      }

      exclude_files = if exclude_file
                        File.readlines(exclude_file).map { |l| l.gsub(/#.*/, '').strip }
                      else
                        []
                      end

      files_to_run = (include_files - exclude_files)
    end

    run_mri_tests(args, files_to_run)
  end
  private :test_mri

  def run_mri_tests(extra_args, test_files, run_options = {})
    truffle_args =  if extra_args.include?('--aot')
                      %W[-XX:YoungGenerationSize=2G -XX:OldGenerationSize=4G -Xhome=#{TRUFFLERUBY_DIR}]
                    else
                      %w[-J-Xmx2G -J-ea -J-esa --jexceptions]
                    end

    env_vars = {
      "EXCLUDES" => "test/mri/excludes",
      "RUBYOPT" => '--disable-gems'
    }

    cext_tests = test_files.select { |f| f.include?("cext/ruby") }
    cext_tests.each do |test|
      test_path = "#{TRUFFLERUBY_DIR}/test/mri/tests/#{test}"
      match = File.read(test_path).match(/^require ['"]c\/(.*?)["']/)
      if match
        compile_dir = if match[1].include?('/')
                        if Dir.exists?("#{MRI_TEST_CEXT_DIR}/#{match[1]}")
                           "#{MRI_TEST_CEXT_DIR}/#{match[1]}"
                        else
                          "#{MRI_TEST_CEXT_DIR}/#{File.dirname(match[1])}"
                        end
                      else
                        "#{MRI_TEST_CEXT_DIR}/#{match[1]}"
                      end
        compile_cext("#{match[1].split("/")[1]}", compile_dir, nil)
      else
        puts "c require not found for cext test: #{test_path}"
      end
    end

    command = %w[test/mri/tests/runner.rb -v --color=never --tty=no -q]
    command.unshift('-Itest/mri/tests/cext')  if !cext_tests.empty?
    run(env_vars, *truffle_args, *extra_args, *command, *test_files, run_options)
  end
  private :run_mri_tests

  def retag(*args)
    options, test_files = args.partition { |a| a.start_with?('-') }
    raise unless test_files.size == 1
    test_file = test_files[0]
    test_classes = File.read(test_file).scan(/class ([\w:]+) < .+TestCase/)
    test_classes.each do |test_class,|
      prefix = "test/mri/excludes/#{test_class.gsub('::', '/')}"
      FileUtils::Verbose.rm_f "#{prefix}.rb"
      FileUtils::Verbose.rm_rf prefix
    end

    puts "1. Tagging tests"
    output_file = "mri_tests.txt"
    run_mri_tests(options, test_file, out: output_file, continue_on_failure: true)

    puts "2. Parsing errors"
    sh "ruby", "tool/parse_mri_errors.rb", output_file

    puts "3. Verifying tests pass"
    run_mri_tests(options, test_file)
  end

  def test_compiler(*args)
    env = {}

    env['TRUFFLERUBYOPT'] = '-Xexceptions.print_java=true'

    if ENV['GRAAL_JS_JAR']
      env['JAVA_OPTS'] = "-cp #{Utilities.find_graal_js}"
    end

    Dir["#{TRUFFLERUBY_DIR}/test/truffle/compiler/*.sh"].sort.each do |test_script|
      if args.empty? or args.include?(File.basename(test_script, ".*"))
        sh env, test_script
      end
    end
  end
  private :test_compiler

  def test_cexts(*args)
    no_openssl = args.delete('--no-openssl')
    no_gems = args.delete('--no-gems')

    # Test tools

    sh RbConfig.ruby, 'test/truffle/cexts/test-preprocess.rb'

    # Test that we can compile and run some basic C code that uses openssl

    if ENV['OPENSSL_PREFIX']
      openssl_cflags = ['-I', "#{ENV['OPENSSL_PREFIX']}/include"]
      openssl_lib = "#{ENV['OPENSSL_PREFIX']}/lib/libssl.#{SO}"
    else
      openssl_cflags = []
      openssl_lib = "libssl.#{SO}"
    end

    unless no_openssl
      sh 'clang', '-c', '-emit-llvm', *openssl_cflags, 'test/truffle/cexts/xopenssl/main.c', '-o', 'test/truffle/cexts/xopenssl/main.bc'
      out, _ = mx_sulong('lli', "-Dpolyglot.llvm.libraries=#{openssl_lib}", 'test/truffle/cexts/xopenssl/main.bc', capture: true)
      raise out.inspect unless out == "5d41402abc4b2a76b9719d911017c592\n"
    end

    # Test that we can compile and run some very basic C extensions

    begin
      output_file = 'cext-output.txt'
      ['minimum', 'method', 'module', 'globals', 'backtraces', 'xopenssl'].each do |gem_name|
        next if gem_name == 'xopenssl' && no_openssl
        dir = "#{TRUFFLERUBY_DIR}/test/truffle/cexts/#{gem_name}"
        ext_dir = "#{dir}/ext/#{gem_name}/"
        compile_cext gem_name, ext_dir, "#{dir}/lib/#{gem_name}/#{gem_name}.su"
        case gem_name
        when 'backtraces'
          run "-I#{dir}/lib", "#{dir}/bin/#{gem_name}", err: output_file, continue_on_failure: true
          unless File.read(output_file)
              .gsub(TRUFFLERUBY_DIR, '')
              .gsub(/\/cext\.rb:(\d+)/, '/cext.rb:n') == File.read("#{dir}/expected.txt")
            abort "c extension #{dir} didn't work as expected"
          end
        else
          run "-I#{dir}/lib", "#{dir}/bin/#{gem_name}", out: output_file
          unless File.read(output_file) == File.read("#{dir}/expected.txt")
            abort "c extension #{dir} didn't work as expected"
          end
        end
      end
    ensure
      File.delete output_file rescue nil
    end

    # Test that we can compile and run some real C extensions

    unless no_gems
      gem_home = "#{gem_test_pack}/gems"

      tests = [
          ['oily_png', ['chunky_png-1.3.6', 'oily_png-1.2.0'], ['oily_png']],
          ['psd_native', ['chunky_png-1.3.6', 'oily_png-1.2.0', 'bindata-2.3.1', 'hashie-3.4.4', 'psd-enginedata-1.1.1', 'psd-2.1.2', 'psd_native-1.1.3'], ['oily_png', 'psd_native']],
          ['nokogiri', [], ['nokogiri']]
      ]

      tests.each do |gem_name, dependencies, libs|
        puts "", gem_name
        next if gem_name == 'nokogiri' # nokogiri totally excluded
        gem_root = "#{TRUFFLERUBY_DIR}/test/truffle/cexts/#{gem_name}"
        ext_dir = Dir.glob("#{gem_home}/gems/#{gem_name}*/")[0] + "ext/#{gem_name}"
        compile_cext gem_name, ext_dir, "#{gem_root}/lib/#{gem_name}/#{gem_name}.su", '-Werror=implicit-function-declaration'

        next if gem_name == 'psd_native' # psd_native is excluded just for running
        run *dependencies.map { |d| "-I#{gem_home}/gems/#{d}/lib" },
            *libs.map { |l| "-I#{TRUFFLERUBY_DIR}/test/truffle/cexts/#{l}/lib" },
            "#{TRUFFLERUBY_DIR}/test/truffle/cexts/#{gem_name}/test.rb", gem_root
      end

      # Tests using gem install to compile the cexts
      sh "test/truffle/cexts/puma/puma.sh"
    end
  end
  private :test_cexts

  def test_report(component)
    test 'specs', '--truffle-formatter', component
    sh 'ant', '-f', 'spec/buildTestReports.xml'
  end
  private :test_report

  def check_test_port
    lsof = `lsof -i :14873`
    unless lsof.empty?
      STDERR.puts 'Someone is already listening on port 14873 - our tests can\'t run'
      STDERR.puts lsof
      exit 1
    end
  end

  def test_integration(*args)
    classpath = []

    if ENV['GRAAL_JS_JAR']
      classpath << Utilities.find_graal_js
    end

    if ENV['SL_JAR']
      classpath << Utilities.find_sl
    end

    env = {}
    unless classpath.empty?
      env['JAVA_OPTS'] = "-cp #{classpath.join(':')}"
    end

    tests_path             = "#{TRUFFLERUBY_DIR}/test/truffle/integration"
    single_test            = !args.empty?
    test_names             = single_test ? '{' + args.join(',') + '}' : '*'

    Dir["#{tests_path}/#{test_names}.sh"].sort.each do |test_script|
      check_test_port
      sh env, test_script
    end
  end
  private :test_integration

  def test_gems(*args)
    gem_test_pack

    env = {}
    if ENV['GRAAL_JS_JAR']
      env['JAVA_OPTS'] = "-cp #{Utilities.find_graal_js}"
    end

    tests_path             = "#{TRUFFLERUBY_DIR}/test/truffle/gems"
    single_test            = !args.empty?
    test_names             = single_test ? '{' + args.join(',') + '}' : '*'

    Dir["#{tests_path}/#{test_names}.sh"].sort.each do |test_script|
      check_test_port
      sh env, test_script
    end
  end
  private :test_gems

  def test_ecosystem(*args)
    gem_test_pack

    tests_path             = "#{TRUFFLERUBY_DIR}/test/truffle/ecosystem"
    single_test            = !args.empty?
    test_names             = single_test ? '{' + args.join(',') + '}' : '*'

    candidates = Dir["#{tests_path}/#{test_names}.sh"].sort
    if candidates.empty?
      targets = Dir["#{tests_path}/*.sh"].sort.map { |f| File.basename(f, ".*") }
      puts "No targets found by pattern #{test_names}. Available targets: "
      targets.each { |t| puts " * #{t}" }
      exit 1
    end
    success = candidates.all? do |test_script|
      sh test_script, continue_on_failure: true
    end
    exit success
  end
  private :test_ecosystem

  def test_bundle(*args)
    if RbConfig::CONFIG['host_os'] =~ /sunos|solaris/i
      # TODO (pitr-ch 08-May-2017): fix workaround using tar, it's broken on Solaris "tar: C: unknown function modifier"
      puts 'skipping on Solaris'
      return
    end

    require 'tmpdir'

    no_sulong = args.delete '--no-sulong'
    gems    = [{ name:   'algebrick',
                 url:    'https://github.com/pitr-ch/algebrick.git',
                 commit: '89cf71984964ce9cbe6a1f4fb5155144ac56d057' }]

    gems.each do |gem|
      gem_name = gem.fetch(:name)
      temp_dir = Dir.mktmpdir(gem_name)

      begin
        gem_home = File.join(temp_dir, 'gem_home')

        Dir.mkdir(gem_home)
        gem_home = File.realpath gem_home # remove symlinks
        puts "Using temporary GEM_HOME:#{gem_home}"

        Dir.chdir(temp_dir) do
          puts "Cloning gem #{gem_name} into temp directory: #{temp_dir}"
          raw_sh('git', 'clone', gem.fetch(:url))
        end

        Dir.chdir(gem_checkout = File.join(temp_dir, gem_name)) do
          raw_sh('git', 'checkout', gem.fetch(:commit)) if gem.key?(:commit)

          environment = Utilities.no_gem_vars_env.merge(
            'GEM_HOME' => gem_home,
            # add bin from gem_home to PATH
            'PATH'     => [File.join(gem_home, 'bin'), ENV['PATH']].join(File::PATH_SEPARATOR))

          openssl_options = no_sulong ? %w[-Xpatching_openssl=true] : []

          run(environment, '-Xexceptions.print_java=true', *openssl_options,
              '-S', 'gem', 'install', '--no-document', 'bundler', '-v', '1.14.6', '--backtrace')
          run(environment, '-Xexceptions.print_java=true', *openssl_options,
              '-J-Xmx512M','-S', 'bundle', 'install')
          run(environment, '-Xexceptions.print_java=true', *openssl_options,
              '-S', 'bundle', 'exec', 'rake')
        end
      ensure
        FileUtils.remove_entry temp_dir
      end
    end
  end

  def mspec(*args)
    super(*args)
  end

  def test_specs(command, *args)
    env_vars = {}
    options = []

    case command
    when 'run'
      options += %w[--excl-tag fails]
    when 'tag'
      options += %w[--add fails --fail --excl-tag fails]
    when 'untag'
      options += %w[--del fails --pass]
      command = 'tag'
    when 'tag_all'
      options += %w[--unguarded --all --dry-run --add fails]
      command = 'tag'
    else
      raise command
    end

    if args.first == 'fast'
      args.shift
      options += %w[--excl-tag slow]
    end

    if args.delete('--aot')
      verify_aot_bin!

      options += %w[--excl-tag graalvm --excl-tag aot]
      options << '-t' << ENV['AOT_BIN']
      options << '-T-XX:YoungGenerationSize=2G'
      options << '-T-XX:OldGenerationSize=4G'
      options << "-T-Xhome=#{TRUFFLERUBY_DIR}"
    end

    if args.delete('--graal')
      javacmd, javacmd_options = Utilities.find_graal_javacmd_and_options
      env_vars["JAVACMD"] = javacmd
      options.concat %w[--excl-tag graalvm]
      options.concat javacmd_options.map { |o| "-T#{o}" }
    end

    if args.delete('--jdebug')
      options << "-T#{JDEBUG}"
    end

    if args.delete('--jexception') || args.delete('--jexceptions')
      options << "-T#{JEXCEPTION}"
    end

    if args.delete('--truffle-formatter')
      options += %w[--format spec/truffle_formatter.rb]
    end

    if ENV['CI']
      options += %w[--format specdoc]
    end

    mspec env_vars, command, *options, *args
  end
  private :test_specs

  def test_tck(*args)
    mx 'rubytck', *args
  end
  private :test_tck

  def gem_test_pack
    name = "truffleruby-gem-test-pack-#{TRUFFLERUBY_GEM_TEST_PACK_VERSION}"
    test_pack = File.expand_path(name, TRUFFLERUBY_DIR)
    unless Dir.exist?(test_pack)
      $stderr.puts "Downloading latest gem test pack..."

      # To update these files contact Manuel Zach for infra and Gilles Duboscq for lafo
      if build_url = ENV['BUILD_URL']
        base = build_url[%r{^https?://[^/]+/}] + "slavefiles2/truffleruby"
      else
        base = "https://lafo.ssw.uni-linz.ac.at/pub/graal-external-deps/"
      end

      url = "#{base}/#{name}.tar.gz"
      archive = "#{test_pack}.tar.gz"
      sh 'curl', '-L', '-o', archive, url
      sh 'tar', '-zxf', archive
    end
    puts test_pack
    test_pack
  end
  alias_method :'gem-test-pack', :gem_test_pack

  def tag(path, *args)
    return tag_all(*args) if path == 'all'
    test_specs('tag', path, *args)
  end

  # Add tags to all given examples without running them. Useful to avoid file exclusions.
  def tag_all(*args)
    test_specs('tag_all', *args)
  end
  private :tag_all

  def untag(path, *args)
    puts
    puts "WARNING: untag is currently not very reliable - run `jt test #{[path,*args] * ' '}` after and manually annotate any new failures"
    puts
    test_specs('untag', path, *args)
  end

  def build_stats(attribute, *args)
    use_json = args.delete '--json'

    value = case attribute
      when 'binary-size'
        build_stats_aot_binary_size(*args)
      when 'build-time'
        build_stats_aot_build_time(*args)
      when 'runtime-compilable-methods'
        build_stats_aot_runtime_compilable_methods(*args)
      else
        raise ArgumentError, attribute
      end

    if use_json
      puts JSON.generate({ attribute => value })
    else
      puts "#{attribute}: #{value}"
    end
  end

  def build_stats_aot_binary_size(*args)
    if File.exist?(ENV['AOT_BIN'].to_s)
      File.size(ENV['AOT_BIN']) / 1024.0 / 1024.0
    else
      -1
    end
  end

  def build_stats_aot_build_time(*args)
    if File.exist?('aot-build.log')
      log = File.read('aot-build.log')
      log =~ /\[total\]: (?<build_time>.+) ms/m
      Float($~[:build_time].gsub(',', '')) / 1000.0
    else
      -1
    end
  end

  def build_stats_aot_runtime_compilable_methods(*args)
    if File.exist?('aot-build.log')
      log = File.read('aot-build.log')
      log =~ /(?<method_count>\d+) method\(s\) included for runtime compilation/m
      Integer($~[:method_count])
    else
      -1
    end
  end

  def metrics(command, *args)
    trap(:INT) { puts; exit }
    args = args.dup
    case command
    when 'alloc'
      metrics_alloc *args
    when 'minheap'
      metrics_minheap *args
    when 'maxrss'
      metrics_maxrss *args
    when 'instructions'
      metrics_aot_instructions *args
    when 'time'
      metrics_time *args
    else
      raise ArgumentError, command
    end
  end

  def metrics_alloc(*args)
    use_json = args.delete '--json'
    samples = []
    METRICS_REPS.times do
      Utilities.log '.', "sampling\n"
      out, err = run '-J-Dtruffleruby.metrics.memory_used_on_exit=true', '-J-verbose:gc', *args, capture: true, no_print_cmd: true
      samples.push memory_allocated(out+err)
    end
    Utilities.log "\n", nil
    range = samples.max - samples.min
    error = range / 2
    median = samples.min + error
    human_readable = "#{Utilities.human_size(median)} ± #{Utilities.human_size(error)}"
    if use_json
      puts JSON.generate({
          samples: samples,
          median: median,
          error: error,
          human: human_readable
      })
    else
      puts human_readable
    end
  end

  def memory_allocated(trace)
    allocated = 0
    trace.lines do |line|
      case line
      when /(\d+)K->(\d+)K/
        before = $1.to_i * 1024
        after = $2.to_i * 1024
        collected = before - after
        allocated += collected
      when /^allocated (\d+)$/
        allocated += $1.to_i
      end
    end
    allocated
  end

  def metrics_minheap(*args)
    use_json = args.delete '--json'
    heap = 10
    Utilities.log '>', "Trying #{heap} MB\n"
    until can_run_in_heap(heap, *args)
      heap += 10
      Utilities.log '>', "Trying #{heap} MB\n"
    end
    heap -= 9
    heap = 1 if heap == 0
    successful = 0
    loop do
      if successful > 0
        Utilities.log '?', "Verifying #{heap} MB\n"
      else
        Utilities.log '+', "Trying #{heap} MB\n"
      end
      if can_run_in_heap(heap, *args)
        successful += 1
        break if successful == METRICS_REPS
      else
        heap += 1
        successful = 0
      end
    end
    Utilities.log "\n", nil
    human_readable = "#{heap} MB"
    if use_json
      puts JSON.generate({
          min: heap,
          human: human_readable
      })
    else
      puts human_readable
    end
  end

  def can_run_in_heap(heap, *command)
    run("-J-Xmx#{heap}M", *command, err: '/dev/null', out: '/dev/null', no_print_cmd: true, continue_on_failure: true, timeout: 60)
  end

  def metrics_maxrss(*args)
    verify_aot_bin!

    use_json = args.delete '--json'
    samples = []

    METRICS_REPS.times do
      Utilities.log '.', "sampling\n"

      max_rss_in_mb = if LINUX
                        out, err = raw_sh('/usr/bin/time', '-v', '--', ENV['AOT_BIN'], *args, capture: true, no_print_cmd: true)
                        err =~ /Maximum resident set size \(kbytes\): (?<max_rss_in_kb>\d+)/m
                        Integer($~[:max_rss_in_kb]) / 1024.0
                      elsif MAC
                        out, err = raw_sh('/usr/bin/time', '-l', '--', ENV['AOT_BIN'], *args, capture: true, no_print_cmd: true)
                        err =~ /(?<max_rss_in_bytes>\d+)\s+maximum resident set size/m
                        Integer($~[:max_rss_in_bytes]) / 1024.0 / 1024.0
                      else
                        raise "Can't measure RSS on this platform."
                      end

      samples.push(maxrss: max_rss_in_mb)
    end
    Utilities.log "\n", nil

    results = {}
    samples[0].each_key do |region|
      region_samples = samples.map { |s| s[region] }
      mean = region_samples.inject(:+) / samples.size
      human = "#{region} #{mean.round(2)} MB"
      results[region] = {
          samples: region_samples,
          mean: mean,
          human: human
      }
      if use_json
        file = STDERR
      else
        file = STDOUT
      end
      file.puts region[/\s*/] + human
    end
    if use_json
      puts JSON.generate(Hash[results.map { |key, values| [key, values] }])
    end
  end

  def metrics_aot_instructions(*args)
    verify_aot_bin!

    use_json = args.delete '--json'

    out, err = raw_sh('perf', 'stat', '-e', 'instructions', '--', ENV['AOT_BIN'], *args, capture: true, no_print_cmd: true)

    err =~ /(?<instruction_count>[\d,]+)\s+instructions/m
    instruction_count = $~[:instruction_count].gsub(',', '')

    Utilities.log "\n", nil
    human_readable = "#{instruction_count} instructions"
    if use_json
      puts JSON.generate({
          instructions: Integer(instruction_count),
          human: human_readable
      })
    else
      puts human_readable
    end
  end

  def metrics_time(*args)
    use_json = args.delete '--json'
    samples = []
    aot = args.include? '--aot'
    metrics_time_option = "#{'-J' unless aot}-Dtruffleruby.metrics.time=true"
    METRICS_REPS.times do
      Utilities.log '.', "sampling\n"
      start = Time.now
      out, err = run metrics_time_option, '--no-core-load-path', *args, capture: true, no_print_cmd: true
      finish = Time.now
      samples.push get_times(err, finish - start)
    end
    Utilities.log "\n", nil
    results = {}
    samples[0].each_key do |region|
      region_samples = samples.map { |s| s[region] }
      mean = region_samples.inject(:+) / samples.size
      human = "#{'%.3f' % mean} #{region.strip}"
      results[region.strip] = {
          samples: region_samples,
          mean: mean,
          human: human
      }
      if use_json
        STDERR.puts region[/\s*/] + human
      else
        STDOUT.puts region[/\s*/] + human
      end
    end
    if use_json
      puts JSON.generate(results)
    end
  end

  def get_times(trace, total)
    indent = ' '
    times = {
      'total' => 0,
      "#{indent}jvm" => 0,
    }
    depth = 0
    run_depth = -1
    accounted_for = 0
    trace.lines do |line|
      if line =~ /^(.+) (\d+\.\d+)$/
        region = $1
        time = $2.to_f
        if region.start_with? 'before-'
          depth += 1
          key = (indent * depth + region['before-'.size..-1])
          raise key if times.key? key
          times[key] = time
          run_depth = depth if region == 'before-run'
        elsif region.start_with? 'after-'
          key = (indent * depth + region['after-'.size..-1])
          start = times[key]
          raise "#{region} without matching before: #{key.inspect} #{times.inspect}" unless start
          elapsed = time - start
          if depth == run_depth+1
            accounted_for += elapsed
          elsif region == 'after-run'
            times[indent * (depth+1) + 'unaccounted'] = elapsed - accounted_for
          end
          depth -= 1
          times[key] = elapsed
        end
      end
    end
    if main = times["#{indent}main"]
      times["#{indent}jvm"] = total - main
    end
    times['total'] = total
    times
  end

  def benchmark(*args)
    args.map! do |a|
      if a.include?('.rb')
        benchmark = Utilities.find_benchmark(a)
        raise 'benchmark not found' unless File.exist?(benchmark)
        benchmark
      else
        a
      end
    end

    benchmark_ruby = ENV['JT_BENCHMARK_RUBY']

    run_args = []

    if args.delete('--aot') || (ENV.has_key?('JT_BENCHMARK_RUBY') && (ENV['JT_BENCHMARK_RUBY'] == ENV['AOT_BIN']))
      run_args.push '-XX:YoungGenerationSize=1G'
      run_args.push '-XX:OldGenerationSize=2G'
      run_args.push "-Xhome=#{TRUFFLERUBY_DIR}"

      # We already have a mechanism for setting the Ruby to benchmark, but elsewhere we use AOT_BIN with the "--aot" flag.
      # Favor JT_BENCHMARK_RUBY to AOT_BIN, but try both.
      benchmark_ruby ||= ENV['AOT_BIN']

      unless File.exist?(benchmark_ruby.to_s)
        raise "JT_BENCHMARK_RUBY or AOT_BIN must point at an AOT build of TruffleRuby"
      end
    end

    unless benchmark_ruby
      run_args.push '--graal' unless args.delete('--no-graal') || args.include?('list')
      run_args.push '-J-Dgraal.TruffleCompilationExceptionsAreFatal=true'
    end

    run_args.push "-I#{Utilities.find_gem('benchmark-ips')}/lib" rescue nil
    run_args.push "#{TRUFFLERUBY_DIR}/bench/benchmark-interface/bin/benchmark"
    run_args.push *args

    if benchmark_ruby
      sh benchmark_ruby, *run_args
    else
      run *run_args
    end
  end

  def where(*args)
    case args.shift
    when 'repos'
      args.each do |a|
        puts Utilities.find_repo(a)
      end
    end
  end

  def install(name)
    case name
    when "graal", "graal-core"
      install_graal
    else
      raise "Unknown how to install #{what}"
    end
  end

  def install_graal
    raise "Installing graal is only available on Linux and macOS currently" unless LINUX || MAC

    build

    env_file = "mx.truffleruby/env"
    unless !File.exist?(env_file) || File.readlines(env_file).grep(/MX_BINARY_SUITES=/).empty?
      abort "You need to remove the MX_BINARY_SUITES line from #{env_file}"
    end

    dir = File.expand_path("..", TRUFFLERUBY_DIR)
    Dir.chdir(dir) do
      if LINUX
        jvmci_version = "jvmci-0.33"
        jvmci_grep = "#{dir}/openjdk1.8.0*#{jvmci_version}"
        if Dir[jvmci_grep].empty?
          puts "Downloading JDK8 with JVMCI"
          jvmci_releases = "https://github.com/dougxc/openjdk8-jvmci-builder/releases/download"
          filename = "openjdk1.8.0_141-#{jvmci_version}-linux-amd64.tar.gz"
          raw_sh "wget", "#{jvmci_releases}/#{jvmci_version}/#{filename}", "-O", filename
          raw_sh "tar", "xf", filename
        end
        java_home = Dir[jvmci_grep].sort.first
      elsif MAC
        jvmci_version = "jvmci-0.32"
        puts "You need to download manually the latest JVMCI-enabled JDK at"
        puts "http://www.oracle.com/technetwork/oracle-labs/program-languages/downloads/index.html"
        puts "Download the file named labsjdk-8u141-#{jvmci_version}-darwin-amd64.tar.gz"
        puts "And move it to the directory #{dir}"
        puts "When done, enter 'done':"
        begin
          print "> "
          done = STDIN.gets
        end until done.chomp == "done"
        archive = Dir["#{dir}/labsjdk-*darwin*.tar.gz"].sort.first
        abort "Could not find the JVMCI-enabled JDK" unless archive
        raw_sh "tar", "xf", archive
        java_home = Dir["#{dir}/labsjdk1.8.0*"].sort.first
      end

      abort "Could not find the extracted JDK" unless java_home
      java_home = File.expand_path(java_home)

      puts "Testing JDK"
      raw_sh "#{java_home}/bin/java", "-version"

      puts "Building graal"
      Dir.chdir("#{dir}/graal/compiler") do
        File.write("mx.compiler/env", "JAVA_HOME=#{java_home}\n")
        mx "build"
      end

      puts "Running with Graal"
      run "--graal", "-e", "p Truffle.graal?"

      puts
      puts "To run TruffleRuby with Graal, use:"
      puts "$ #{TRUFFLERUBY_DIR}/tool/jt.rb ruby --graal ..."
    end
  end

  def next(*args)
    puts `cat spec/tags/core/**/**.txt | grep 'fails:'`.lines.sample
  end

  def native_launcher
    platform = UNAME.downcase
    sh "cc", "-o", "tool/native_launcher_#{platform}", "tool/native_launcher.c"
  end
  alias :'native-launcher' :native_launcher

  def check_dsl_usage
    mx 'clean'
    # We need to build with -parameters to get parameter names
    mx 'build', '--force-javac', '-A-parameters'
    run({ "TRUFFLE_CHECK_DSL_USAGE" => "true" }, '-Xlazy.default=false', '-e', 'exit')
  end

  def rubocop(*args)
    gem_home = "#{gem_test_pack}/rubocop-gems"
    env = {
      "GEM_HOME" => gem_home,
      "GEM_PATH" => gem_home,
      "PATH" => "#{gem_home}/bin:#{ENV['PATH']}"
    }
    sh env, "ruby", "#{gem_home}/bin/rubocop", *args
  end

  def check_parser
    build('parser')
    diff, _err = sh 'git', 'diff', 'src/main/java/org/truffleruby/parser/parser/RubyParser.java', :err => :out, capture: true
    unless diff.empty?
      STDERR.puts "DIFF:"
      STDERR.puts diff
      abort "RubyParser.y must be modified and RubyParser.java regenerated by 'jt build parser'"
    end
  end

  def lint(*args)
    check_dsl_usage unless args.delete '--no-build'
    rubocop
    sh "tool/lint.sh"
    check_parser
  end

  def verify_aot_bin!
    unless File.exist?(ENV['AOT_BIN'].to_s)
      raise "AOT_BIN must point at an AOT build of TruffleRuby"
    end
  end

end

class JT
  include Commands

  def main(args)
    args = args.dup

    if args.empty? or %w[-h -help --help].include? args.first
      help
      exit
    end

    case args.first
    when "rebuild"
      send(args.shift)
    when "build"
      command = [args.shift]
      while ['cexts', 'parser', 'options', '--no-openssl'].include?(args.first)
        command << args.shift
      end
      send(*command)
    end

    return if args.empty?

    commands = Commands.public_instance_methods(false).map(&:to_s)

    command, *rest = args
    command = "command_#{command}" if %w[p puts].include? command

    abort "no command matched #{command.inspect}" unless commands.include?(command)

    begin
      send(command, *rest)
    rescue
      puts "Error during command: #{args*' '}"
      raise $!
    end
  end
end

if $0 == __FILE__
  abort "Do not run #{$0} with TruffleRuby itself, use MRI or some other Ruby." if RUBY_ENGINE == "truffleruby"
  JT.new.main(ARGV)
end
