module Truffle::Patching
  extend self

  DIR = "#{Truffle::Boot.ruby_home}/lib/patches"
  ORIGINALS = {}

  def patches
    @patches ||= begin
      patches = {}
      Dir.foreach(DIR) do |file|
        unless file[0] == '.'
          path = "#{DIR}/#{file}"
          patches[file] = path if File.directory?(path)
        end
      end
      patches
    end
  end

  def log(name, path)
    Truffle::System.log :PATCH,
                        "patching '#{name}' by inserting directory '#{path}' in LOAD_PATH before the original paths"
  end

  def insert_patching_dir(name, *paths)
    path = Truffle::Patching.patches[name]
    if path
      insertion_point = paths.
          map { |gem_require_path| $LOAD_PATH.index gem_require_path }.
          min
      ORIGINALS[name] = paths
      Truffle::Patching.log(name, path)
      $LOAD_PATH.insert insertion_point, path if $LOAD_PATH[insertion_point-1] != path
      true
    else
      false
    end
  end

  def require_original(file)
    relative_path = file[DIR.length+1..-1]
    slash = relative_path.index '/'
    name = relative_path[0...slash]
    require_path = relative_path[slash+1..-1]

    original = ORIGINALS.fetch(name).find do |original_path|
      path = "#{original_path}/#{require_path}"
      break path if File.file?(path)
    end

    Kernel.require original
  end

  def install_gem_activation_hook
    Gem::Specification.class_eval do
      alias_method :activate_without_truffle_patching, :activate

      def activate
        result = activate_without_truffle_patching
        Truffle::Patching.insert_patching_dir name, *full_require_paths
        result
      end
    end
  end

end
