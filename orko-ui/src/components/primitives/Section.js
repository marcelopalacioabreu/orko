import React from "react"
import styled from "styled-components"
import { color } from "styled-system"
import { Icon } from "semantic-ui-react"
import Heading from "./Heading"
import Href from "./Href"

const SectionBox = styled.section`
  ${color} margin: 0;
  padding: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
`

const SectionHeadingBox = styled.div`
  background-color: ${props => props.theme.colors.backgrounds[3]};
  vertical-align: middle;
  padding: ${props => props.theme.space[2] + "px"};
  display: flex;
  justify-content: space-between;
  border-bottom: 1px solid rgba(0, 0, 0, 0.3);
  box-shadow: 0 2px 15px 0 rgba(0, 0, 0, 0.15);
`

const SectionInner = styled.section`
  padding-top: ${props => (props.nopadding ? 0 : "10px")};
  padding-bottom: ${props =>
    props.nopadding ? 0 : props.theme.space[2] + "px"};
  padding-left: ${props => (props.nopadding ? 0 : props.theme.space[2] + "px")};
  padding-right: ${props =>
    props.nopadding ? 0 : props.theme.space[2] + "px"};
  flex: 1
  position: relative;
  overflow-x: ${props =>
    props.scroll === "horizontal" || props.scroll === "both"
      ? "scroll"
      : "auto"};
  overflow-y: ${props =>
    props.scroll === "vertical" || props.scroll === "both" ? "scroll" : "auto"};
`

class Section extends React.Component {
  render() {
    return (
      <SectionBox>
        <SectionHeadingBox data-orko={"section/" + this.props.id + "/tabs"}>
          <Heading p={0} my={0} ml={0} mr={3} color="heading">
            {this.props.draggable && (
              <Icon
                name="arrows alternate"
                className="dragMe"
                title="Drag to move"
              />
            )}
            {this.props.heading}
          </Heading>
          <div>
            {this.props.buttons && this.props.buttons()}
            {this.props.onHide && (
              <Href
                data-orko={"section/" + this.props.id + "/hide"}
                ml={2}
                onClick={this.props.onHide}
                title="Hide this panel (it can be shown again from View Settings)"
              >
                <Icon name="hide" />
              </Href>
            )}
          </div>
        </SectionHeadingBox>
        <SectionInner
          data-orko={"section/" + this.props.id}
          scroll={this.props.scroll}
          expand={this.props.expand}
          nopadding={this.props.nopadding}
        >
          {this.props.children}
        </SectionInner>
      </SectionBox>
    )
  }
}

export default Section
