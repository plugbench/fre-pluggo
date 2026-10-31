{
  description = "A frecency store for plugbench, wrapping fre";
  inputs = {
    flake-utils.url = "github:numtide/flake-utils";
  };
  outputs = { self, nixpkgs, flake-utils }:
    (flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        fre-pluggo = pkgs.callPackage ./derivation.nix {};
      in {
        packages = {
          default = fre-pluggo;
          inherit fre-pluggo;
        };
        checks = {
          end-to-end = pkgs.runCommand "fre-pluggo-end-to-end" {
            nativeBuildInputs = [ fre-pluggo pkgs.fre pkgs.nats-server ];
          } ''
            HOME=$TMPDIR bash ${./tests/end-to-end.sh}
            touch $out
          '';
        };
        devShells.default = pkgs.mkShell {
          nativeBuildInputs = with pkgs; [
            babashka
            fre
            nats-server
          ];
        };
    })) // {
      overlays.default = final: prev: {
        fre-pluggo = prev.callPackage ./derivation.nix {};
      };
    };
}
